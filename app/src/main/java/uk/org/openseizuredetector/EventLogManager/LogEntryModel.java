package uk.org.openseizuredetector.EventLogManager;

import java.util.Date;

/**
 * Our LogEntryModel class which will have fields like id, name, contact number
 * and email and corresponding getter and setter methods.
 * **/
public class LogEntryModel {

	private int id;
	private Date date;
    private int alarmState;
    private String dataJSON;
    private String note;


	public int getId() {
		return id;
	}

	public void setId(int id) {
		this.id = id;
	}

    public int getAlarmState() {
        return alarmState;
    }

    public void setAlarmState(int alarmState) {
        this.alarmState = alarmState;
    }

	public String getNote() {
		return note;
	}

	public void setNote(String note) {
		this.note = note;
	}

	public Date getDate() {
		return date;
	}

	public void setDate(Date date) {
		this.date = date;
	}

	public String getDataJSON() { return dataJSON; }

	public void setDataJSON(String dataJSON) { this.dataJSON = dataJSON; }
}
