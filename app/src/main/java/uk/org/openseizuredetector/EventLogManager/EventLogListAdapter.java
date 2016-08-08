package uk.org.openseizuredetector.EventLogManager;

import java.util.ArrayList;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import uk.org.openseizuredetector.R;

public class EventLogListAdapter extends BaseAdapter {
	EventLogManager dm;
	ArrayList<LogEntryModel> logEntryModelList;
	LayoutInflater inflater;
	Context _context;

	public EventLogListAdapter(Context context) {

		logEntryModelList = new ArrayList<LogEntryModel>();
		_context = context;
		inflater = (LayoutInflater) context
				.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
		dm = new EventLogManager(_context);
		logEntryModelList = dm.getAllData();

	}

	@Override
	public void notifyDataSetChanged() {
		super.notifyDataSetChanged();
		//refetching the new data from database
		logEntryModelList = dm.getAllData();

	}

	public void delRow(int delPosition) {

		dm.deleteRow(logEntryModelList.get(delPosition).getId());
		logEntryModelList.remove(delPosition);

	}

	@Override
	public int getCount() {
		return logEntryModelList.size();
	}

	@Override
	public Object getItem(int position) {
		return logEntryModelList.get(position);
	}

	@Override
	public long getItemId(int position) {
		return 0;
	}

	@Override
	public View getView(int position, View convertView, ViewGroup parent) {
		ViewHolder vHolder;
		if (convertView == null) {
			convertView = inflater.inflate(R.layout.log_entry_layout, null);
			vHolder = new ViewHolder();

			vHolder.date = (TextView) convertView
					.findViewById(R.id.event_date);
			vHolder.alarmState = (TextView) convertView
					.findViewById(R.id.event_alarmState);
			vHolder.note = (TextView) convertView
					.findViewById(R.id.event_note);
			vHolder.dataJSON = (TextView) convertView
					.findViewById(R.id.event_dataJSON);
			convertView.setTag(vHolder);
		} else {
			vHolder = (ViewHolder) convertView.getTag();
		}

		LogEntryModel eventObj = logEntryModelList.get(position);

		//vHolder.date.setText(eventObj.getDate().toString());
		vHolder.alarmState.setText(eventObj.getAlarmState());
		vHolder.note.setText(eventObj.getNote());
		vHolder.dataJSON.setText(eventObj.getDataJSON());

		return convertView;
	}

	class ViewHolder {
		TextView date,alarmState,note,dataJSON;
	}

}
