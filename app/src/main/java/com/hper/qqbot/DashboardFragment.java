package com.hper.qqbot;

import android.content.*;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.fragment.app.Fragment;

public class DashboardFragment extends Fragment {
    private TextView tvStatus, tvRecentLog;
    private View statusDot;
    private Button btnStart, btnStop;
    private boolean isRunning = false;

    private final BroadcastReceiver logReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (getActivity() == null) return;
            String log = intent.getStringExtra(BotService.EXTRA_LOG);
            if (log != null && tvRecentLog != null) {
                getActivity().runOnUiThread(() -> {
                    String[] lines = log.split("\n");
                    StringBuilder sb = new StringBuilder();
                    int start = Math.max(0, lines.length - 8);
                    for (int i = start; i < lines.length; i++) {
                        sb.append(lines[i]).append("\n");
                    }
                    tvRecentLog.setText(sb.toString().trim());
                });
            }
        }
    };

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_dashboard, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        tvStatus = view.findViewById(R.id.tvStatus);
        statusDot = view.findViewById(R.id.statusDot);
        tvRecentLog = view.findViewById(R.id.tvRecentLog);
        btnStart = view.findViewById(R.id.btnStart);
        btnStop = view.findViewById(R.id.btnStop);

        btnStart.setOnClickListener(v -> startBot());
        btnStop.setOnClickListener(v -> stopBot());

        updateUI(false);
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

    private void startBot() {
        Intent intent = new Intent(requireContext(), BotService.class);
        intent.setAction(BotService.ACTION_START);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) { requireContext().startForegroundService(intent); } else { requireContext().startService(intent); }
        updateUI(true);
    }

    private void stopBot() {
        Intent intent = new Intent(requireContext(), BotService.class);
        intent.setAction(BotService.ACTION_STOP);
        requireContext().startService(intent);
        updateUI(false);
    }

    private void refreshLog() {
        Intent intent = new Intent(requireContext(), BotService.class);
        intent.setAction(BotService.ACTION_GET_LOG);
        requireContext().startService(intent);
    }

    private void updateUI(boolean running) {
        isRunning = running;
        if (getActivity() == null) return;
        getActivity().runOnUiThread(() -> {
            btnStart.setEnabled(!running);
            btnStop.setEnabled(running);

            GradientDrawable dot = (GradientDrawable) statusDot.getBackground();
            if (running) {
                tvStatus.setText(R.string.status_running);
                dot.setColor(requireContext().getColor(R.color.status_running));
            } else {
                tvStatus.setText(R.string.status_stopped);
                dot.setColor(requireContext().getColor(R.color.status_stopped));
            }
        });
    }
}
