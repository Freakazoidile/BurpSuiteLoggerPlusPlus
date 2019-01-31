package loggerplusplus;

import burp.IBurpExtenderCallbacks;
import com.coreyd97.BurpExtenderUtilities.IGsonProvider;
import com.coreyd97.BurpExtenderUtilities.Preferences;
import com.google.gson.reflect.TypeToken;
import loggerplusplus.filter.ColorFilter;
import loggerplusplus.filter.LogFilter;
import static loggerplusplus.Globals.*;

import loggerplusplus.filter.SavedFilter;
import loggerplusplus.userinterface.LogTableColumn;
import loggerplusplus.userinterface.VariableViewPanel;

import java.util.*;

public class PreferenceFactory {

    private Preferences prefs;
    private IGsonProvider gsonProvider;
    private HashMap<UUID, ColorFilter> defaultColorFilters;
    private ArrayList<LogTableColumn> defaultlogTableColumns;

    private PreferenceFactory(IGsonProvider gsonProvider, IBurpExtenderCallbacks callbacks){
        this.gsonProvider = gsonProvider;
        prefs = new Preferences(LoggerPlusPlus.gsonProvider, LoggerPlusPlus.callbacks);
    }

    public static Preferences build(IGsonProvider gsonProvider, IBurpExtenderCallbacks callbacks){
        PreferenceFactory preferenceFactory = new PreferenceFactory(gsonProvider, callbacks);
        preferenceFactory.registerTypeAdapters();
        preferenceFactory.createDefaults();
        preferenceFactory.registerSettings();
        return preferenceFactory.prefs;
    }

    private void createDefaults(){
        defaultColorFilters = this.gsonProvider.getGson().fromJson(
                Globals.DEFAULT_COLOR_FILTERS_JSON, new TypeToken<HashMap<UUID, ColorFilter>>(){}.getType());
        defaultlogTableColumns = this.gsonProvider.getGson().fromJson(
                Globals.DEFAULT_LOG_TABLE_COLUMNS_JSON, new TypeToken<List<LogTableColumn>>() {}.getType());
    }

    private void registerTypeAdapters(){
        LoggerPlusPlus.gsonProvider.registerTypeAdapter(LogFilter.class, new LogFilter.FilterSerializer());
        LoggerPlusPlus.gsonProvider.registerTypeAdapter(LogTableColumn.class, new LogTableColumn.ColumnSerializer());
    }

    private void registerSettings() {
        prefs.addSetting(PREF_LOG_TABLE_SETTINGS, new TypeToken<List<LogTableColumn>>() {}.getType(), defaultlogTableColumns);
        prefs.addSetting(PREF_LAST_USED_VERSION, Double.class, Globals.VERSION);
        prefs.addSetting(PREF_IS_DEBUG, Boolean.class, false);
        prefs.addSetting(PREF_UPDATE_ON_STARTUP, Boolean.class, true);
        prefs.addSetting(PREF_ENABLED, Boolean.class, true);
        prefs.addSetting(PREF_RESTRICT_TO_SCOPE, Boolean.class, false);
        prefs.addSetting(PREF_LOG_GLOBAL, Boolean.class, true);
        prefs.addSetting(PREF_LOG_PROXY, Boolean.class, true);
        prefs.addSetting(PREF_LOG_SPIDER, Boolean.class, true);
        prefs.addSetting(PREF_LOG_INTRUDER, Boolean.class, true);
        prefs.addSetting(PREF_LOG_SCANNER, Boolean.class, true);
        prefs.addSetting(PREF_LOG_REPEATER, Boolean.class, true);
        prefs.addSetting(PREF_LOG_SEQUENCER, Boolean.class, true);
        prefs.addSetting(PREF_LOG_EXTENDER, Boolean.class, true);
        prefs.addSetting(PREF_LOG_TARGET_TAB, Boolean.class, true);
        prefs.addSetting(PREF_COLOR_FILTERS, new TypeToken<Map<UUID, ColorFilter>>() {}.getType(), defaultColorFilters);
        prefs.addSetting(PREF_SAVED_FILTERS, new TypeToken<List<SavedFilter>>() {}.getType(), new ArrayList<SavedFilter>());
        prefs.addSetting(PREF_SORT_COLUMN, Integer.class, -1);
        prefs.addSetting(PREF_SORT_ORDER, String.class, "ASCENDING");
        prefs.addSetting(PREF_RESPONSE_TIMEOUT, Integer.class, 60000);
        prefs.addSetting(PREF_MAXIMUM_ENTRIES, Integer.class, 5000);
        prefs.addSetting(PREF_LAYOUT, VariableViewPanel.View.class, VariableViewPanel.View.VERTICAL);
        prefs.addSetting(PREF_MESSAGE_VIEW_LAYOUT, VariableViewPanel.View.class, VariableViewPanel.View.HORIZONTAL);
        prefs.addSetting(PREF_SEARCH_THREADS, Integer.class, 5);
        prefs.addSetting(PREF_AUTO_IMPORT_PROXY_HISTORY, Boolean.class, false);
        prefs.addSetting(PREF_LOG_OTHER_LIVE, Boolean.class, true);
        prefs.addSetting(PREF_ELASTIC_ADDRESS, String.class, "127.0.0.1");
        prefs.addSetting(PREF_ELASTIC_PORT, Integer.class, 9300);
        prefs.addSetting(PREF_ELASTIC_CLUSTER_NAME, String.class, "elasticsearch");
        prefs.addSetting(PREF_ELASTIC_INDEX, String.class, "logger");
        prefs.addSetting(PREF_ELASTIC_DELAY, Integer.class, 120);

        prefs.addVolatileSetting(PREF_AUTO_SAVE, Boolean.class, false);
        prefs.addVolatileSetting(PREF_AUTO_SCROLL, Boolean.class, true);
    }

}
