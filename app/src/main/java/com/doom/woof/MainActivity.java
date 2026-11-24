package com.doom.woof;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.doom.woof.databinding.ActivityMainBinding;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Launcher: copies woof.pk3 from assets, lets the user pick an IWAD and add-ons, and starts GameActivity.
 *
 * WAD files live in Android/data/com.doom.woof/files/wads
 * (the "Import" row copies files there).
 */
public class MainActivity extends AppCompatActivity {
    private static final String PREFS = "woof_launcher";
    private static final String PREF_IWAD = "iwad";
    private static final String PREF_ARGS = "extra_args";
    private static final String PREF_ADDONS = "addons";

    private ActivityMainBinding binding;
    private SharedPreferences prefs;
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private File wadsDir;
    private final ArrayList<File> iwads = new ArrayList<>();
    private final ArrayList<File> addons = new ArrayList<>();
    private final ArrayList<File> selectedAddons = new ArrayList<>();

    private final ActivityResultLauncher<String[]> importLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenMultipleDocuments(),
                    this::importFiles);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        File ext = getExternalFilesDir("wads");
        wadsDir = ext != null ? ext : new File(getFilesDir(), "wads");
        //noinspection ResultOfMethodCallIgnored
        wadsDir.mkdirs();

        binding.textInfo.setText(getString(R.string.wads_folder_hint, wadsDir.getAbsolutePath()));
        binding.editArgs.setText(prefs.getString(PREF_ARGS, ""));

        binding.buttonImport.setOnClickListener(v -> importLauncher.launch(new String[] { "*/*" }));
        binding.buttonAddons.setOnClickListener(v -> chooseAddons());
        binding.buttonControls.setOnClickListener(
                v -> startActivity(new Intent(this, ControlsEditorActivity.class)));
        binding.buttonPlay.setOnClickListener(v -> play());

        // The game needs woof.pk3 before it starts: put it in the internal files dir (where Woof looks for it).
        io.execute(() -> {
            try {
                installBaseResources();
            } catch (IOException e) {
                runOnUiThread(() -> Toast.makeText(this,
                        getString(R.string.error_pk3, e.getMessage()), Toast.LENGTH_LONG).show());
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        rescan();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdown();
        binding = null;
    }

    // ------------------------------------------------------------ resources

    private void installBaseResources() throws IOException {
        File out = new File(getFilesDir(), "woof.pk3");
        File tmp = new File(getFilesDir(), "woof.pk3.tmp");
        try (InputStream in = getAssets().open("woof.pk3");
             OutputStream os = new FileOutputStream(tmp)) {
            copy(in, os);
        }
        if (out.exists() && !out.delete()) {
            throw new IOException("cannot replace " + out);
        }
        if (!tmp.renameTo(out)) {
            throw new IOException("cannot rename " + tmp);
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
    }

    // ------------------------------------------------------------ WAD list

    private void rescan() {
        iwads.clear();
        addons.clear();

        File[] files = wadsDir.listFiles();
        if (files != null) {
            Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            for (File f : files) {
                if (!f.isFile()) {
                    continue;
                }
                String name = f.getName().toLowerCase(Locale.ROOT);
                if (name.endsWith(".wad")) {
                    if (isIwad(f)) {
                        iwads.add(f);
                    } else {
                        addons.add(f);
                    }
                } else if (name.endsWith(".pk3") || name.endsWith(".zip") || name.endsWith(".deh")
                        || name.endsWith(".bex") || name.endsWith(".lmp")) {
                    addons.add(f);
                }
            }
        }

        ArrayList<String> names = new ArrayList<>();
        for (File f : iwads) {
            names.add(f.getName());
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, names);
        binding.spinnerIwad.setAdapter(adapter);

        String last = prefs.getString(PREF_IWAD, null);
        for (int i = 0; i < iwads.size(); i++) {
            if (iwads.get(i).getName().equals(last)) {
                binding.spinnerIwad.setSelection(i);
            }
        }

        // Restore the selected add-ons that still exist.
        selectedAddons.clear();
        String saved = prefs.getString(PREF_ADDONS, "");
        for (String n : saved.split("\n")) {
            for (File f : addons) {
                if (f.getName().equals(n)) {
                    selectedAddons.add(f);
                }
            }
        }
        updateAddonsText();

        boolean have = !iwads.isEmpty();
        binding.buttonPlay.setEnabled(have);
        binding.textNoIwad.setVisibility(have ? View.GONE : View.VISIBLE);
        binding.buttonAddons.setEnabled(!addons.isEmpty());
    }

    private static boolean isIwad(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] h = new byte[4];
            return in.read(h) == 4 && h[0] == 'I' && h[1] == 'W' && h[2] == 'A' && h[3] == 'D';
        } catch (IOException e) {
            return false;
        }
    }

    private void chooseAddons() {
        String[] items = new String[addons.size()];
        boolean[] checked = new boolean[addons.size()];
        for (int i = 0; i < items.length; i++) {
            items[i] = addons.get(i).getName();
            checked[i] = selectedAddons.contains(addons.get(i));
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.addons)
                .setMultiChoiceItems(items, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    selectedAddons.clear();
                    for (int i = 0; i < checked.length; i++) {
                        if (checked[i]) {
                            selectedAddons.add(addons.get(i));
                        }
                    }
                    updateAddonsText();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void updateAddonsText() {
        if (selectedAddons.isEmpty()) {
            binding.textAddons.setText(R.string.addons_none);
        } else {
            StringBuilder sb = new StringBuilder();
            for (File f : selectedAddons) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(f.getName());
            }
            binding.textAddons.setText(sb);
        }
    }

    // ------------------------------------------------------------ import

    private void importFiles(java.util.List<Uri> uris) {
        if (uris == null || uris.isEmpty()) {
            return;
        }
        io.execute(() -> {
            int ok = 0;
            for (Uri uri : uris) {
                String name = displayName(uri);
                if (name == null) {
                    continue;
                }
                File dst = new File(wadsDir, new File(name).getName());
                try (InputStream in = getContentResolver().openInputStream(uri);
                     OutputStream out = new FileOutputStream(dst)) {
                    if (in == null) {
                        continue;
                    }
                    copy(in, out);
                    ok++;
                } catch (IOException e) {
                    //noinspection ResultOfMethodCallIgnored
                    dst.delete();
                }
            }
            final int copied = ok;
            runOnUiThread(() -> {
                if (binding == null) {
                    return;
                }
                Toast.makeText(this, getString(R.string.imported, copied), Toast.LENGTH_SHORT).show();
                rescan();
            });
        });
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[] { OpenableColumns.DISPLAY_NAME },
                null, null, null)) {
            if (c != null && c.moveToFirst()) {
                return c.getString(0);
            }
        } catch (RuntimeException ignored) {
            // fall through
        }
        String last = uri.getLastPathSegment();
        return last != null ? last : null;
    }

    // ------------------------------------------------------------ launch

    private void play() {
        int pos = binding.spinnerIwad.getSelectedItemPosition();
        if (pos < 0 || pos >= iwads.size()) {
            return;
        }
        File iwad = iwads.get(pos);
        String extra = binding.editArgs.getText().toString().trim();

        StringBuilder saved = new StringBuilder();
        for (File f : selectedAddons) {
            saved.append(f.getName()).append('\n');
        }
        prefs.edit()
                .putString(PREF_IWAD, iwad.getName())
                .putString(PREF_ARGS, extra)
                .putString(PREF_ADDONS, saved.toString())
                .apply();

        ArrayList<String> args = new ArrayList<>();
        args.add("-iwad");
        args.add(iwad.getAbsolutePath());
        if (!selectedAddons.isEmpty()) {
            args.add("-file");
            for (File f : selectedAddons) {
                args.add(f.getAbsolutePath());
            }
        }
        if (!extra.isEmpty()) {
            args.addAll(Arrays.asList(extra.split("\\s+")));
        }

        Intent intent = new Intent(this, GameActivity.class);
        intent.putExtra(GameActivity.EXTRA_ARGS, args.toArray(new String[0]));
        startActivity(intent);
    }
}
