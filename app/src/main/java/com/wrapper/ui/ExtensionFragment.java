package com.wrapper.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.wrapper.R;
import com.wrapper.vpn.BrowsecVpnService;
import com.wrapper.vpn.ext.CrxImporter;
import com.wrapper.vpn.ext.ServerStore;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Вкладка «Расширение»: импорт серверов из браузерного VPN-расширения
 * и переключение между двумя режимами.
 *
 * Оба режима подключаются одним и тем же нативным туннелем.
 * Расширение здесь — только источник адресов.
 */
public class ExtensionFragment extends Fragment {

    private static final int REQ_PICK_CRX = 700;

    private ServerStore store;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private RadioGroup rgMode;
    private RadioButton rbBuiltin;
    private RadioButton rbExt;
    private TextView tvInfo;
    private TextView tvStatus;
    private TextView tvCount;
    private ListView lvServers;
    private Button btnImport;
    private Button btnClear;

    private ServerAdapter adapter;

    static ExtensionFragment newInstance() {
        return new ExtensionFragment();
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = ServerStore.get(requireContext());
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.tab_extension, container, false);

        rgMode = v.findViewById(R.id.rg_mode);
        rbBuiltin = v.findViewById(R.id.rb_mode_builtin);
        rbExt = v.findViewById(R.id.rb_mode_extension);
        tvInfo = v.findViewById(R.id.tv_ext_info);
        tvStatus = v.findViewById(R.id.tv_ext_status);
        tvCount = v.findViewById(R.id.tv_ext_count);
        lvServers = v.findViewById(R.id.lv_ext_servers);
        btnImport = v.findViewById(R.id.btn_import_ext);
        btnClear = v.findViewById(R.id.btn_clear_ext);

        boolean extMode = store.isExtensionMode();
        rbBuiltin.setChecked(!extMode);
        rbExt.setChecked(extMode);

        rgMode.setOnCheckedChangeListener((g, checkedId) -> {
            if (checkedId == R.id.rb_mode_extension && !store.hasImported()) {
                Toast.makeText(getContext(),
                        "Сначала импортируйте расширение", Toast.LENGTH_SHORT).show();
                rbBuiltin.setChecked(true);
                return;
            }
            String mode = checkedId == R.id.rb_mode_extension
                    ? ServerStore.MODE_EXTENSION : ServerStore.MODE_BUILTIN;
            store.setMode(mode);
            updateStatus("Режим: " + (extMode(mode) ? "из расширения" : "встроенный"));
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).onModeChanged(mode);
            }
        });

        btnImport.setOnClickListener(vv -> pickExtension());
        btnClear.setOnClickListener(vv -> {
            store.clearImported();
            refresh();
            Toast.makeText(getContext(), "Данные расширения удалены", Toast.LENGTH_SHORT).show();
        });

        adapter = new ServerAdapter();
        lvServers.setAdapter(adapter);

        refresh();
        return v;
    }

    private boolean extMode(String mode) {
        return ServerStore.MODE_EXTENSION.equals(mode);
    }

    private void pickExtension() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/octet-stream", "application/zip", "application/x-chrome-extension"
        });
        try {
            startActivityForResult(intent, REQ_PICK_CRX);
        } catch (Exception e) {
            Toast.makeText(getContext(), "Нет файлового менеджера", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_CRX) return;
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return;

        final Uri uri = data.getData();
        showBusy("Читаю расширение...");

        executor.execute(() -> {
            String resultText;
            int count = 0;
            try {
                CrxImporter importer = new CrxImporter(requireContext());
                CrxImporter.Result res = importer.importFrom(uri);
                count = store.saveImport(res);
                resultText = res.note != null ? res.note : "Готово";
            } catch (Exception e) {
                resultText = "Ошибка: " + e.getMessage();
            }
            final String text = resultText;
            final int saved = count;

            main.post(() -> {
                if (!isAdded()) return;
                hideBusy();
                if (saved > 0) {
                    store.setMode(ServerStore.MODE_EXTENSION);
                    rbExt.setChecked(true);
                }
                refresh();
                updateStatus(text);
                if (getActivity() instanceof MainActivity) {
                    ((MainActivity) getActivity()).onModeChanged(store.getMode());
                }
                Toast.makeText(getContext(),
                        saved > 0 ? "Импортировано серверов: " + saved : "Серверы не найдены",
                        Toast.LENGTH_LONG).show();
            });
        });
    }

    private void showBusy(String text) {
        tvStatus.setText(text);
        tvStatus.setTextColor(0xFFffb300);
        tvStatus.setVisibility(View.VISIBLE);
        btnImport.setEnabled(false);
    }

    private void hideBusy() {
        tvStatus.setVisibility(View.GONE);
        btnImport.setEnabled(true);
    }

    private void updateStatus(String text) {
        if (tvStatus == null) return;
        tvStatus.setText(text);
        tvStatus.setTextColor(0xFF808090);
        tvStatus.setVisibility(View.VISIBLE);
    }

    private void refresh() {
        boolean ext = store.isExtensionMode();
        rbBuiltin.setChecked(!ext);
        rbExt.setChecked(ext);

        if (store.hasImported()) {
            String name = store.getExtensionName();
            String ver = store.getExtensionVersion();
            tvInfo.setText(name + (ver.isEmpty() ? "" : " v" + ver)
                    + " — серверов: " + store.getImportedCount());
            tvInfo.setTextColor(0xFF4CAF50);
            btnClear.setEnabled(true);
        } else {
            tvInfo.setText("Расширение не импортировано");
            tvInfo.setTextColor(0xFF808090);
            btnClear.setEnabled(false);
        }
        rbExt.setEnabled(store.hasImported());

        int n = store.getImportedCount();
        tvCount.setText(n > 0 ? n + " серверов в режиме расширения" : "—");

        if (adapter != null) adapter.refresh();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (store != null) refresh();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    private class ServerAdapter extends BaseAdapter {
        private List<BrowsecVpnService.VpnServer> items;

        ServerAdapter() {
            refresh();
        }

        void refresh() {
            items = store.getImportedServers();
            notifyDataSetChanged();
        }

        @Override
        public int getCount() { return items.size(); }

        @Override
        public BrowsecVpnService.VpnServer getItem(int p) { return items.get(p); }

        @Override
        public long getItemId(int p) { return p; }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            TextView tv = convert != null ? (TextView) convert : new TextView(getContext());
            if (convert == null) {
                tv.setPadding(24, 14, 24, 14);
                tv.setTextSize(12f);
            }
            BrowsecVpnService.VpnServer s = getItem(pos);
            String active = store.isExtensionMode() ? "● " : "   ";
            tv.setText(active + s.flag + " " + s.name + " (" + s.host + ":" + s.port + ")");
            tv.setTextColor(0xFFc0c0c0);
            return tv;
        }
    }
}
