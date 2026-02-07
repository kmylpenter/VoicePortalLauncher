package com.voiceportal.launcher;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;
import java.util.List;

public class AppListAdapter extends BaseAdapter {
    private final Context context;
    private final List<AppConfig> apps;

    public AppListAdapter(Context context, List<AppConfig> apps) {
        this.context = context;
        this.apps = apps;
    }

    @Override
    public int getCount() { return apps.size(); }

    @Override
    public Object getItem(int pos) { return apps.get(pos); }

    @Override
    public long getItemId(int pos) { return pos; }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        if (convertView == null) {
            convertView = LayoutInflater.from(context)
                .inflate(R.layout.item_app_card, parent, false);
        }

        AppConfig app = apps.get(position);

        TextView nameView = convertView.findViewById(R.id.app_name);
        TextView descView = convertView.findViewById(R.id.app_description);
        TextView portView = convertView.findViewById(R.id.app_port);
        TextView modeView = convertView.findViewById(R.id.app_mode);

        nameView.setText(app.name);
        descView.setText(app.description);
        portView.setText(String.format(":%d", app.port));
        modeView.setText("mode: " + app.voicePortalMode);

        return convertView;
    }
}
