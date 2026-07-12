package com.hper.qqbot;

import android.content.*;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.fragment.app.Fragment;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;

public class SettingsFragment extends Fragment {
    private TextInputEditText etQQ, etSignToken, etApiUrl, etApiKey, etModel, etPrompt;
    private TextView tvDataDir;
    private SharedPreferences prefs;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        try {
            prefs = requireContext().getSharedPreferences("bot_settings", Context.MODE_PRIVATE);

            etQQ = view.findViewById(R.id.etQQ);
            etSignToken = view.findViewById(R.id.etSignToken);
            etApiUrl = view.findViewById(R.id.etApiUrl);
            etApiKey = view.findViewById(R.id.etApiKey);
            etModel = view.findViewById(R.id.etModel);
            etPrompt = view.findViewById(R.id.etPrompt);
            tvDataDir = view.findViewById(R.id.tvDataDir);

            if (prefs != null) {
                etQQ.setText(prefs.getString("qq_number", "3255201290"));
                etSignToken.setText(prefs.getString("sign_token", "456f7527-34d0-4a24-8860-74d77021a90e"));
                etApiUrl.setText(prefs.getString("api_url", "https://api.stepfun.com/step_plan/v1/chat/completions"));
                etApiKey.setText(prefs.getString("api_key", ""));
                etModel.setText(prefs.getString("model", "step-3.7-flash"));
                etPrompt.setText(prefs.getString("prompt", "你是一个友好的AI助手，运行在QQ机器人上。\n回复简洁有用，不超过200字。使用中文回复。"));
            }

            tvDataDir.setText(requireContext().getFilesDir().getAbsolutePath());

            MaterialButton btnSave = view.findViewById(R.id.btnSave);
            btnSave.setOnClickListener(v -> saveSettings());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void saveSettings() {
        try {
            prefs.edit()
                .putString("qq_number", etQQ.getText().toString().trim())
                .putString("sign_token", etSignToken.getText().toString().trim())
                .putString("api_url", etApiUrl.getText().toString().trim())
                .putString("api_key", etApiKey.getText().toString().trim())
                .putString("model", etModel.getText().toString().trim())
                .putString("prompt", etPrompt.getText().toString())
                .apply();
            if (getView() != null) {
                Snackbar.make(getView(), "✅ 设置已保存", Snackbar.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
