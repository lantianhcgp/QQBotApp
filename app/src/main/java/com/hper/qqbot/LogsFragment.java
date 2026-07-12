package com.hper.qqbot;

import android.content.*;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.fragment.app.Fragment;

public class LogsFragment extends Fragment {
    private TextView tvLog;
    private ScrollView scrollView;

    private final BroadcastReceiver logReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (getActivity() == null) return;
            String log = intent.getStringExtra(BotService.EXTRA_LOG);
            if (log != null && tvLog != null) {
                getActivity().runOnUiThread(() -> {
                    tvLog.setText(log);
                    scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
                });
            }
        }
    };

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_logs, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        tvLog = view.findViewById(R.id.tvLog);
        scrollView = view.findViewById(R.id.scrollView);

        view.findViewById(R.id.btnClearLog).setOnClickListener(v -> {
            tvLog.setText(R.string.logs_empty);
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        requireContext().registerReceiver(logReceiver, new IntentFilter("com.hper.qqbot.LOG_UPDATE"), Context.RECEIVER_NOT_EXPORTED);
        refreshLog();
    }

    @Override
    public void onPause() {
        super.onPause();
        requireContext().unregisterReceiver(logReceiver);
    }

    private void refreshLog() {
        Intent intent = new Intent(requireContext(), BotService.class);
        intent.setAction(BotService.ACTION_GET_LOG);
        requireContext().startService(intent);
    }
}
