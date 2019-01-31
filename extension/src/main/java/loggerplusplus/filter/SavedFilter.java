package loggerplusplus.filter;

/**
 * Created by corey on 19/07/17.
 */
public class SavedFilter {
    private String name;
    private LogFilter filter;
    private String filterString;

    public SavedFilter(){

    }

    public LogFilter getFilter() {
        return filter;
    }

    public void setFilter(LogFilter filter) {
        this.filter = filter;
        if(filter != null)
            this.filterString = filter.toString();
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getFilterString() {
        return filterString;
    }

    public void setFilterString(String filterString) {
        this.filterString = filterString;
    }

    @Override
    public boolean equals(Object o) {
        if(o instanceof SavedFilter){
            SavedFilter other = (SavedFilter) o;
            return other.name.equals(name) && other.filterString.equals(filterString);
        }else{
            return super.equals(o);
        }
    }
}
