package com.niimbot.preplabels;

public class PrepItem implements Comparable<PrepItem> {
    private String id;
    private String name;
    private int days;
    private String durationLabel;

    public PrepItem(String id, String name, int days, String durationLabel) {
        this.id = id;
        this.name = name;
        this.days = days;
        this.durationLabel = durationLabel;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public int getDays() { return days; }
    public String getDurationLabel() { return durationLabel; }

    @Override
    public int compareTo(PrepItem o) {
        return this.name.compareToIgnoreCase(o.name);
    }
}
