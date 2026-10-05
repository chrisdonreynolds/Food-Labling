package com.niimbot.preplabels;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity implements NiimbotB1Printer.PrinterListener, PrepAdapter.AdapterListener {

    private static final String PREFS_NAME = "NiimbotPrepPrefs";
    private static final String KEY_ITEMS = "prep_items_json";
    private static final int PERMISSION_REQ_CODE = 101;

    private NiimbotB1Printer printer;
    private PrepAdapter adapter;
    private List<PrepItem> itemList = new ArrayList<>();

    private View statusDot;
    private TextView tvStatus;
    private Button btnConnect;
    private RecyclerView recyclerView;

    private static final String[][] DURATION_OPTIONS = {
        {"1 Day", "1"}, {"2 Days", "2"}, {"3 Days", "3"}, {"4 Days", "4"},
        {"5 Days", "5"}, {"6 Days", "6"}, {"7 Days (1 Week)", "7"}, {"8 Days", "8"},
        {"9 Days", "9"}, {"10 Days", "10"}, {"11 Days", "11"}, {"12 Days", "12"},
        {"13 Days", "13"}, {"14 Days (2 Weeks)", "14"}, {"3 Weeks (21 Days)", "21"},
        {"4 Weeks (28 Days)", "28"}
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusDot = findViewById(R.id.statusDot);
        tvStatus = findViewById(R.id.tvStatus);
        btnConnect = findViewById(R.id.btnConnect);
        recyclerView = findViewById(R.id.recyclerView);

        printer = new NiimbotB1Printer(this, this);

        loadSavedItems();

        GridLayoutManager gridLayoutManager = new GridLayoutManager(this, 2);
        gridLayoutManager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                if (adapter != null && adapter.getItemViewType(position) == PrepAdapter.TYPE_ADD_BUTTON) {
                    return 2;
                }
                return 1;
            }
        });

        recyclerView.setLayoutManager(gridLayoutManager);
        adapter = new PrepAdapter(itemList, this);
        recyclerView.setAdapter(adapter);

        btnConnect.setOnClickListener(v -> {
            if (printer.isConnected()) {
                printer.disconnect();
            } else {
                checkPermissionsAndConnect();
            }
        });
    }

    private void checkPermissionsAndConnect() {
        List<String> perms = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN);
            perms.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            perms.add(Manifest.permission.ACCESS_FINE_LOCATION);
            perms.add(Manifest.permission.BLUETOOTH);
            perms.add(Manifest.permission.BLUETOOTH_ADMIN);
        }

        List<String> needed = new ArrayList<>();
        for (String p : perms) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                needed.add(p);
            }
        }

        if (!needed.isEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toArray(new String[0]), PERMISSION_REQ_CODE);
        } else {
            printer.startScanAndConnect();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQ_CODE) {
            boolean allGranted = true;
            for (int r : grantResults) {
                if (r != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                printer.startScanAndConnect();
            } else {
                Toast.makeText(this, "Bluetooth permissions are required", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void loadSavedItems() {
        itemList.clear();
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String json = prefs.getString(KEY_ITEMS, null);
        if (json != null) {
            try {
                JSONArray arr = new JSONArray(json);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject obj = arr.getJSONObject(i);
                    itemList.add(new PrepItem(
                        obj.getString("id"),
                        obj.getString("name"),
                        obj.getInt("days"),
                        obj.getString("durationLabel")
                    ));
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        if (itemList.isEmpty()) {
            itemList.add(new PrepItem("1", "Cooked Chicken", 4, "4 Days"));
            itemList.add(new PrepItem("2", "House Sauce", 7, "7 Days (1 Wk)"));
            itemList.add(new PrepItem("3", "Prepped Lettuce", 2, "2 Days"));
            itemList.add(new PrepItem("4", "Sliced Tomatoes", 3, "3 Days"));
        }
    }

    private void saveItems() {
        try {
            JSONArray arr = new JSONArray();
            for (PrepItem it : itemList) {
                JSONObject obj = new JSONObject();
                obj.put("id", it.getId());
                obj.put("name", it.getName());
                obj.put("days", it.getDays());
                obj.put("durationLabel", it.getDurationLabel());
                arr.put(obj);
            }
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_ITEMS, arr.toString())
                .apply();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onItemClick(PrepItem item) {
        if (!printer.isConnected()) {
            Toast.makeText(this, "Connecting to Niimbot B1...", Toast.LENGTH_SHORT).show();
            checkPermissionsAndConnect();
            return;
        }

        Bitmap label = printer.generateLabelBitmap(item.getName(), item.getDays(), item.getDurationLabel());
        printer.printLabel(label);
    }

    @Override
    public void onItemDelete(PrepItem item, int position) {
        itemList.remove(position);
        saveItems();
        adapter.sortAlphabetical();
        Toast.makeText(this, "Deleted " + item.getName(), Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onAddButtonClick() {
        showAddDialog();
    }

    private void showAddDialog() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_item, null);
        EditText etName = dialogView.findViewById(R.id.etItemName);
        Spinner spDuration = dialogView.findViewById(R.id.spDuration);
        Button btnCancel = dialogView.findViewById(R.id.btnCancel);
        Button btnAdd = dialogView.findViewById(R.id.btnAdd);

        List<String> spinnerLabels = new ArrayList<>();
        for (String[] opt : DURATION_OPTIONS) {
            spinnerLabels.add(opt[0]);
        }

        ArrayAdapter<String> spinnerAdapter = new ArrayAdapter<>(
            this, android.R.layout.simple_spinner_dropdown_item, spinnerLabels
        );
        spDuration.setAdapter(spinnerAdapter);
        spDuration.setSelection(2);

        AlertDialog dialog = new AlertDialog.Builder(this)
            .setView(dialogView)
            .create();

        btnCancel.setOnClickListener(v -> dialog.dismiss());
        btnAdd.setOnClickListener(v -> {
            String name = etName.getText().toString().trim();
            if (name.isEmpty()) {
                etName.setError("Name required");
                return;
            }

            int selectedIdx = spDuration.getSelectedItemPosition();
            String label = DURATION_OPTIONS[selectedIdx][0];
            int days = Integer.parseInt(DURATION_OPTIONS[selectedIdx][1]);

            itemList.add(new PrepItem(String.valueOf(System.currentTimeMillis()), name, days, label));
            saveItems();
            adapter.sortAlphabetical();
            dialog.dismiss();
            Toast.makeText(this, "Added " + name, Toast.LENGTH_SHORT).show();
        });

        dialog.show();
    }

    @Override
    public void onConnectionStateChange(boolean connected, String deviceName) {
        if (connected) {
            statusDot.setBackgroundColor(Color.parseColor("#10B981"));
            tvStatus.setText("Connected");
            btnConnect.setText("Disconnect");
            btnConnect.setBackgroundColor(Color.parseColor("#334155"));
            Toast.makeText(this, "Connected", Toast.LENGTH_SHORT).show();
        } else {
            statusDot.setBackgroundColor(Color.parseColor("#64748B"));
            tvStatus.setText("Disconnected");
            btnConnect.setText("Connect");
            btnConnect.setBackgroundColor(Color.parseColor("#3B82F6"));
        }
    }

    @Override
    public void onPrintProgress(String status) {
        // Ignored: Header status strictly displays Connected or Disconnected
    }

    @Override
    public void onError(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }
}
