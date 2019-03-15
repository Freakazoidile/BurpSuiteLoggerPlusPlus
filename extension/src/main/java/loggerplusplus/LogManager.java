package loggerplusplus;

import burp.*;
import loggerplusplus.filter.ColorFilter;
import loggerplusplus.userinterface.LogViewPanel;

import javax.swing.*;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static loggerplusplus.Globals.*;
import static loggerplusplus.LoggerPlusPlus.callbacks;
import static loggerplusplus.LoggerPlusPlus.preferences;

/**
 * Created by corey on 07/09/17.
 */
public class LogManager implements IHttpListener, IProxyListener {
    static final SimpleDateFormat sdf = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
    private static final String randIdentifier = String.format("%02d", (int)Math.floor((Math.random()*100)));
    private static final Pattern uuidPattern = Pattern.compile("\\$LPP:(\\d\\d):(.*?)\\$");

    private final ArrayList<LogEntry> logEntries;
    private AtomicInteger pendingImport;

    private HashMap<Integer, LogEntry.PendingRequestEntry> pendingProxyRequests;
    private HashMap<UUID, LogEntry.PendingRequestEntry> pendingToolRequests;
    private ArrayList<LogEntryListener> logEntryListeners;
    private ExecutorService executorService;
    //Stats
    private int totalRequests = 0;
    private short lateResponses = 0;

    private Future importFuture;

    LogManager(){
        logEntries = new ArrayList<>();
        pendingImport = new AtomicInteger(0);
        logEntryListeners = new ArrayList<>();
        pendingProxyRequests = new HashMap<>();
        pendingToolRequests = new HashMap<>();
        LoggerPlusPlus.callbacks.getProxyHistory();

        executorService = new ThreadPoolExecutor(0, 2147483647, 60L, TimeUnit.SECONDS, new SynchronousQueue()){
            @Override
            protected void afterExecute(Runnable r, Throwable t) {
                super.afterExecute(r, t);
                if (t == null && r instanceof Future<?>) {
                    try {
                        Future<?> future = (Future<?>) r;
                        if (future.isDone()) {
                            future.get();
                        }
                    } catch (CancellationException ce) {
                        t = ce;
                    } catch (ExecutionException ee) {
                        t = ee.getCause();
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
                if (t != null) {
                    t.printStackTrace();
                }
            }
        };

        //Create incomplete request cleanup thread so map doesn't get too big.
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        executor.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                long timeNow = new Date().getTime();
                Set<Integer> keys = new HashSet<>(pendingProxyRequests.keySet());
                synchronized (pendingProxyRequests){
                    for (Integer reference : keys) {
                        long entryTime = pendingProxyRequests.get(reference).requestDateTime.getTime();
                        long responseTimeout = (long) LoggerPlusPlus.preferences.getSetting(PREF_RESPONSE_TIMEOUT);
                        if(timeNow - entryTime > responseTimeout){
                            pendingProxyRequests.remove(reference);
                        }
                    }
                }
                Set<UUID> toolKeys = new HashSet<>(pendingToolRequests.keySet());
                synchronized (pendingToolRequests){
                    for (UUID reference : toolKeys) {
                        long entryTime = pendingToolRequests.get(reference).requestDateTime.getTime();
                        long responseTimeout = (long) LoggerPlusPlus.preferences.getSetting(PREF_RESPONSE_TIMEOUT);
                        if(timeNow - entryTime > responseTimeout){
                            pendingToolRequests.remove(reference);
                        }
                    }
                }
            }
        },30000, 30000, TimeUnit.MILLISECONDS);
    }



    @Override
    public void processHttpMessage(final int toolFlag, final boolean messageIsRequest, final IHttpRequestResponse requestResponse) {
        if(toolFlag == IBurpExtenderCallbacks.TOOL_PROXY) return;
        if(requestResponse == null || !(Boolean) preferences.getSetting(PREF_ENABLED)) return;
        Date nowDate = new Date();

        if(!(Boolean) LoggerPlusPlus.preferences.getSetting(PREF_LOG_OTHER_LIVE)){
            //Submit normally, we're not tracking requests and responses separately.
            if(!messageIsRequest) {
                processHttpMessage(new LogEntry(), toolFlag, false, requestResponse);
            }
            return;
        }

        final LogEntry logEntry;
        if(messageIsRequest){
            UUID uuid = UUID.randomUUID();
            logEntry = new LogEntry.PendingRequestEntry(uuid);
            String originalComment = requestResponse.getComment() != null ? requestResponse.getComment() : "";
            requestResponse.setComment(originalComment + "$LPP:" + randIdentifier + ":" + uuid + "$");
            synchronized (pendingToolRequests){
                pendingToolRequests.put(uuid, (LogEntry.PendingRequestEntry) logEntry);
            }
            processHttpMessage(logEntry, toolFlag, true, requestResponse);
        }else{
            //Pull the uuid we stored in the comment
            if(requestResponse.getComment() != null) {
                Matcher matcher = uuidPattern.matcher(requestResponse.getComment());
                if (matcher.find() && matcher.group(1).equals(randIdentifier)) {
                    UUID uuid = UUID.fromString(matcher.group(2));
                    synchronized (pendingToolRequests) {
                        logEntry = pendingToolRequests.remove(uuid);
                    }
                    if (logEntry != null) {
                        requestResponse.setComment(matcher.replaceAll(""));
                        logEntry.setResponseDateTime(nowDate);
                        processHttpMessage(logEntry, toolFlag, false, requestResponse);
                    }
                } else {
                    //No longer in the map. Perhaps got cleaned out? Drop the response...
                }
            }else{
                //No longer in the map. Perhaps got cleaned out? Drop the response...
            }
        }
    }

    //Wrapper to allow a custom LogEntry to be passed as a parameter
    //Custom LogEntry used when importing proxy history.
    //messageIsRequest is removed as not needed.
    public void processHttpMessage(final LogEntry logEntry, final int toolFlag, final boolean messageIsRequest, final IHttpRequestResponse requestResponse){
        executorService.submit(() -> {
            try {
                if (toolFlag != IBurpExtenderCallbacks.TOOL_PROXY || logEntry.isImported) {
                    IRequestInfo analyzedReq = LoggerPlusPlus.callbacks.getHelpers().analyzeRequest(requestResponse);
                    URL uUrl = analyzedReq.getUrl();
                    if (!isValidTool(toolFlag) || !shouldLog(uUrl)) {
                        return;
                    }
                    if (!(Boolean) LoggerPlusPlus.preferences.getSetting(PREF_LOG_OTHER_LIVE) || logEntry.isImported) { //If we're not tracking req/resp separate
                        IHttpRequestResponsePersisted savedReqResp = LoggerPlusPlus.callbacks.saveBuffersToTempFiles(requestResponse);
                        logEntry.processRequest(toolFlag, savedReqResp, uUrl, analyzedReq, null);
                        if (requestResponse.getResponse() != null) logEntry.processResponse(savedReqResp);
                        addNewRequest(logEntry, true);
                    } else {
                        if (messageIsRequest) {
                            logEntry.processRequest(toolFlag, requestResponse, uUrl, analyzedReq, null);
                            addNewRequest(logEntry, false);
                        } else {
                            updatePendingRequest((LogEntry.PendingRequestEntry) logEntry, requestResponse);
                        }
                    }
                }
            }finally {
                if (logEntry.isImported) {
//                    pendingImport.getAndDecrement();
                }
            }
        });
    }


    @Override
    public void processProxyMessage(final boolean messageIsRequest, final IInterceptedProxyMessage proxyMessage) {
        //REQUEST AND RESPONSE SEPARATE
        if(proxyMessage == null || !(Boolean) preferences.getSetting(PREF_ENABLED)) return;
        Date nowDate = new Date();

        final LogEntry.PendingRequestEntry logEntry;
        if(messageIsRequest){
            logEntry = new LogEntry.PendingRequestEntry();
            synchronized (pendingProxyRequests) {
                pendingProxyRequests.put(proxyMessage.getMessageReference(), logEntry);
            }
        }else{
            synchronized (pendingProxyRequests) {
                logEntry = pendingProxyRequests.remove(proxyMessage.getMessageReference());
            }
            if(logEntry == null){
                lateResponses++;
                if(totalRequests > 100 && ((float)lateResponses)/totalRequests > 0.17){
                    MoreHelp.showWarningMessage(lateResponses + " responses have been delivered after the Logger++ timeout. Consider increasing this value.");
                    //Reset late responses to prevent message being displayed again so soon.
                    lateResponses = 0;
                }
                return;
            }
            logEntry.setResponseDateTime(nowDate);
        }

        executorService.submit(new Runnable() {
            @Override
            public void run() {
                IHttpRequestResponse requestResponse = proxyMessage.getMessageInfo();
                IRequestInfo analyzedReq = LoggerPlusPlus.callbacks.getHelpers().analyzeRequest(requestResponse);
                URL uUrl = analyzedReq.getUrl();
                int toolFlag = LoggerPlusPlus.callbacks.TOOL_PROXY;
                if (isValidTool(toolFlag) && shouldLog(uUrl)){
                    if(messageIsRequest){
                        //New Proxy Request
                        //We need to change messageInfo when we get a response so do not save to buffers
                        logEntry.processRequest(toolFlag, requestResponse, uUrl, analyzedReq, proxyMessage);
                        addNewRequest(logEntry, false); //Request added without response
                    }else{
                        //Existing Proxy Request, update existing
                        updatePendingRequest(logEntry, requestResponse);
                    }
                }
            }
        });
    }

    private void addNewRequest(LogEntry logEntry, boolean hasResponse){
        //After handling request / response logEntries generation.
        //Add to grepTable / modify existing entry.
        HashMap<UUID,ColorFilter> colorFilters = (HashMap<UUID, ColorFilter>) LoggerPlusPlus.preferences.getSetting(PREF_COLOR_FILTERS);
        for (ColorFilter colorFilter : colorFilters.values()) {
            logEntry.testColorFilter(colorFilter, false);
        }

        synchronized (logEntries) {
            while (logEntries.size() >= getMaximumEntries()) {
                final LogEntry removed = logEntries.remove(0);
                for (LogEntryListener listener : logEntryListeners) {
                    listener.onRequestRemoved(0, removed);
                }
            }
        }
        int modelIndex;
        synchronized (logEntries) {
            logEntries.add(logEntry);
            modelIndex = logEntries.size()-1;
        }
        for (LogEntryListener listener : logEntryListeners) {
            listener.onRequestAdded(modelIndex, logEntry, hasResponse);
        }
        totalRequests++;
        if(logEntry instanceof LogEntry.PendingRequestEntry){
            ((LogEntry.PendingRequestEntry) logEntry).setLogRow(totalRequests-1);
        }
    }

    private void updatePendingRequest(LogEntry.PendingRequestEntry pendingRequest, IHttpRequestResponse messageInfo) {
        //Fill in gaps of request with response
        if(messageInfo == null) {
            LoggerPlusPlus.callbacks.printError("Warning: Response received with null messageInfo.");
            return;
        }
        pendingRequest.processResponse(messageInfo);


        HashMap<UUID, ColorFilter> colorFilters = (HashMap<UUID, ColorFilter>) LoggerPlusPlus.preferences.getSetting(PREF_COLOR_FILTERS);
        for (ColorFilter colorFilter : colorFilters.values()) {
            pendingRequest.testColorFilter(colorFilter, true);
        }
        for (LogEntryListener logEntryListener : logEntryListeners) {
            logEntryListener.onResponseUpdated(pendingRequest);
        }
    }

    public ArrayList<LogEntry> getLogEntries() {
        return logEntries;
    }

    private boolean isValidTool(int toolFlag){
        return ((Boolean) preferences.getSetting(PREF_LOG_GLOBAL) ||
                ((Boolean) preferences.getSetting(PREF_LOG_PROXY) && toolFlag== IBurpExtenderCallbacks.TOOL_PROXY) ||
                ((Boolean) preferences.getSetting(PREF_LOG_INTRUDER) && toolFlag== IBurpExtenderCallbacks.TOOL_INTRUDER) ||
                ((Boolean) preferences.getSetting(PREF_LOG_REPEATER) && toolFlag== IBurpExtenderCallbacks.TOOL_REPEATER) ||
                ((Boolean) preferences.getSetting(PREF_LOG_SCANNER) && toolFlag== IBurpExtenderCallbacks.TOOL_SCANNER) ||
                ((Boolean) preferences.getSetting(PREF_LOG_SEQUENCER) && toolFlag== IBurpExtenderCallbacks.TOOL_SEQUENCER) ||
                ((Boolean) preferences.getSetting(PREF_LOG_SPIDER) && toolFlag== IBurpExtenderCallbacks.TOOL_SPIDER) ||
                ((Boolean) preferences.getSetting(PREF_LOG_EXTENDER) && toolFlag== IBurpExtenderCallbacks.TOOL_EXTENDER) ||
                ((Boolean) preferences.getSetting(PREF_LOG_TARGET_TAB) && toolFlag== IBurpExtenderCallbacks.TOOL_TARGET));
    }

    private boolean shouldLog(URL url){
        return (!(Boolean) preferences.getSetting(PREF_RESTRICT_TO_SCOPE)
                || LoggerPlusPlus.callbacks.isInScope(url));
    }

    public void reset() {
        synchronized (this.logEntries) {
            this.logEntries.clear();
        }
        synchronized (this.pendingProxyRequests) {
            this.pendingProxyRequests.clear();
        }
        this.lateResponses = 0;
        this.totalRequests = 0;
    }

    public void addLogListener(LogEntryListener listener) {
        logEntryListeners.add(listener);
    }
    public void removeLogListener(LogEntryListener listener) {
        logEntryListeners.remove(listener);
    }
    public ArrayList<LogEntryListener> getLogEntryListeners() {
        return logEntryListeners;
    }

    public int getTotalRequests() {
        return totalRequests;
    }

    public int getMaximumEntries() {
        return (int) LoggerPlusPlus.preferences.getSetting(PREF_MAXIMUM_ENTRIES);
    }

    public void importExisting(IHttpRequestResponse requestResponse) {
        int toolFlag = IBurpExtenderCallbacks.TOOL_PROXY;
        LogEntry logEntry = new LogEntry(true);
        pendingImport.getAndIncrement();
        processHttpMessage(logEntry, toolFlag, false, requestResponse);
    }

    public void importProxyHistory(boolean askConfirmation){
        int result = JOptionPane.OK_OPTION;
        int historySize = callbacks.getProxyHistory().length;
        if(askConfirmation)
            result = MoreHelp.askConfirmMessage("Burp Proxy Import",
                    "Import " + historySize + " items from burp suite proxy history? This will clear the current entries." +
                    "\nLarge imports may take a few minutes to process." +
                    "\nNote: History will be truncated to the maximum entries configured in the options.", new String[]{"Import", "Cancel"});

        if(result == JOptionPane.OK_OPTION) {
            importFuture = executorService.submit(() -> {
                LoggerPlusPlus.instance.getLogManager().reset();
                LogViewPanel logViewPanel = LoggerPlusPlus.instance.getLogViewPanel();
                IHttpRequestResponse[] history = callbacks.getProxyHistory();

                int maxEntries = (int) LoggerPlusPlus.preferences.getSetting(PREF_MAXIMUM_ENTRIES);
                int startIndex = Math.max(0, history.length-maxEntries);
                int importCount = historySize - startIndex;
                logViewPanel.showImportProgress(importCount);

                for (int index = startIndex; index < history.length; index++) {
                    importExisting(history[index]);
                }
                while(pendingImport.get() != 0 && !Thread.currentThread().isInterrupted()){
                    try {
                        Thread.sleep(500);
                        int pending = pendingImport.get();
                        System.out.println("Pending Import: " + pending);
                        logViewPanel.setProgressValue(importCount - pending);
                    } catch (InterruptedException e) {}
                }
                //All imported
                LoggerPlusPlus.instance.getLogViewPanel().showLogTable();
            });
        }
        reset();
    }

    public ExecutorService getExecutorService() {
        return executorService;
    }

    public Future getImportFuture() {
        return importFuture;
    }
}
