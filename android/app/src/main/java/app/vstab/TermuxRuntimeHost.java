package app.vstab;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Supported Termux command API; all workspace code runs in the same Linux VM. */
final class TermuxRuntimeHost {
    static final String PACKAGE = "com.termux";
    static final String PERMISSION = "com.termux.permission.RUN_COMMAND";
    // TermuxConstants.TERMUX_SERVICE.EXTRA_PLUGIN_RESULT_BUNDLE.
    private static final String RESULT_BUNDLE = "result";
    private static final String REQUEST_ID = "app.vstab.termux.request";
    private static final AtomicInteger NEXT_REQUEST = new AtomicInteger(1000);
    private static final Map<String, Completion> PENDING = new ConcurrentHashMap<>();

    interface Completion { void accept(Result result); }

    static final class Result {
        final String status;
        final String message;
        final String details;
        final String transactionSha256;
        final int exitCode;

        Result(String status, String message, String details, String transactionSha256, int exitCode) {
            this.status = status; this.message = message; this.details = details;
            this.transactionSha256 = transactionSha256; this.exitCode = exitCode;
        }
        boolean needsPackage() { return "needs-package".equals(status); }
    }

    static boolean hasTermux(Context context) {
        try { context.getPackageManager().getApplicationInfo(PACKAGE, 0); return true; }
        catch (PackageManager.NameNotFoundException absent) { return false; }
    }

    static boolean permissionGranted(Context context) {
        return context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED;
    }

    static void start(Context context, String controlUrl, String token, String environmentId,
                      String architecture, int sshHostPort, int editorHostPort, Completion completion) {
        if (!controlUrl.matches("http://127\\.0\\.0\\.1:[0-9]{1,5}")
                || !token.matches("[a-zA-Z0-9_-]{32,256}")
                || !environmentId.matches("[a-zA-Z0-9_-]{1,80}")
                || sshHostPort < 1 || sshHostPort > 65535 || editorHostPort < 1 || editorHostPort > 65535) {
            completion.accept(error("The Linux environment connection parameters are invalid.")); return;
        }
        invoke(context, "start", architecture, controlUrl, token, environmentId,
                sshHostPort, editorHostPort, "", completion);
    }

    static void previewPackages(Context context, String architecture, Completion completion) {
        invoke(context, "preview", architecture, "", "", "", 0, 0, "", completion);
    }

    static void preflight(Context context, String architecture, Completion completion) {
        invoke(context, "check", architecture, "", "", "", 0, 0, "", completion);
    }

    static void stop(Context context, String environmentId, String architecture, Completion completion) {
        if (environmentId == null || !environmentId.matches("[a-zA-Z0-9_-]{1,80}")) {
            completion.accept(error("The Linux environment identity is invalid.")); return;
        }
        invoke(context, "stop", architecture, "", "", environmentId, 0, 0, "", completion);
    }

    /** Worker-only bounded teardown. Keep the Android brokers alive until it returns. */
    static Result stopAndWait(Context context, String environmentId, String architecture, long timeoutMillis) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
            throw new IllegalStateException("Termux shutdown must run on the environment worker.");
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Result> result = new AtomicReference<>();
        stop(context, environmentId, architecture, value -> { result.set(value); done.countDown(); });
        try {
            if (done.await(Math.max(1, timeoutMillis), TimeUnit.MILLISECONDS)) return result.get();
            return error("Termux did not confirm a clean shutdown. Its disk was preserved; reconnect or retry Stop.");
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
            return error("Termux shutdown was interrupted. Its disk was preserved.");
        }
    }

    /** Explicit setup action: signed repository metadata only, no package changes. */
    static void refreshPackages(Context context, String architecture, Completion completion) {
        invoke(context, "refresh", architecture, "", "", "", 0, 0, "", completion);
    }

    /** Call only after the user approves this exact package preview in setup. */
    static void installPackages(Context context, String architecture,
                                String approvedTransactionSha256, Completion completion) {
        if (!approvedTransactionSha256.matches("[a-f0-9]{64}")) {
            completion.accept(error("Review the Termux package download before installing.")); return;
        }
        invoke(context, "install", architecture, "", "", "", 0, 0,
                approvedTransactionSha256, completion);
    }

    private static void invoke(Context context, String mode, String architecture,
                               String controlUrl, String token, String environmentId,
                               int sshPort, int editorPort, String approved, Completion completion) {
        if (!hasTermux(context)) { completion.accept(error("Install Termux or choose built-in Linux.")); return; }
        if (!permissionGranted(context)) {
            completion.accept(error("Allow VStab to run commands in Termux, then enable allow-external-apps in Termux settings.")); return;
        }
        if (!"x86_64".equals(architecture) && !"arm64-v8a".equals(architecture) && !"aarch64".equals(architecture)) {
            completion.accept(error("This tablet architecture is not supported by the Linux runtime.")); return;
        }
        String request = UUID.randomUUID().toString();
        PendingIntent pending = null;
        try {
            StringBuilder script = new StringBuilder();
            assignment(script, "VSTAB_MODE_B64", mode);
            assignment(script, "VSTAB_ARCH_B64", architecture);
            assignment(script, "VSTAB_CONTROL_URL_B64", controlUrl);
            assignment(script, "VSTAB_CONTROL_TOKEN_B64", token);
            assignment(script, "VSTAB_ENVIRONMENT_ID_B64", environmentId);
            assignment(script, "VSTAB_SSH_PORT_B64", Integer.toString(sshPort));
            assignment(script, "VSTAB_EDITOR_PORT_B64", Integer.toString(editorPort));
            assignment(script, "VSTAB_APPROVED_TRANSACTION_B64", approved);
            try (InputStream input = context.getAssets().open("linux-runtime/host/termux-launch.sh")) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192]; int count;
                while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
                script.append(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            }
            Intent callback = new Intent(context, ResultReceiver.class);
            callback.putExtra(REQUEST_ID, request);
            pending = PendingIntent.getBroadcast(context, NEXT_REQUEST.getAndIncrement(), callback,
                    PendingIntent.FLAG_ONE_SHOT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : 0));
            Intent intent = new Intent("com.termux.RUN_COMMAND");
            intent.setClassName(PACKAGE, "com.termux.app.RunCommandService");
            intent.putExtra("com.termux.RUN_COMMAND_PATH", "$PREFIX/bin/bash");
            intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[]{"-s"});
            intent.putExtra("com.termux.RUN_COMMAND_STDIN", script.toString());
            intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);
            intent.putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL", "VStab Linux environment");
            intent.putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pending);
            PENDING.put(request, completion);
            context.startService(intent);
        } catch (Exception failure) {
            PENDING.remove(request);
            if (pending != null) pending.cancel();
            // Do not log the intent, its stdin, or exceptions that may echo it.
            completion.accept(error("Termux could not start the Linux setup command. Open Termux, check its command permission and allow-external-apps setting, then retry."));
        }
    }

    private static void assignment(StringBuilder output, String name, String value) {
        output.append(name).append("='").append(Base64.encodeToString(value.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP)).append("'\n");
    }

    private static Result error(String message) { return new Result("error", message, "", "", -1); }

    private static String decode(String value) {
        return new String(Base64.decode(value, Base64.DEFAULT), StandardCharsets.UTF_8);
    }

    public static final class ResultReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            String request = intent.getStringExtra(REQUEST_ID);
            Completion completion = request == null ? null : PENDING.remove(request);
            if (completion == null) return;
            android.os.Bundle bundle = intent.getBundleExtra(RESULT_BUNDLE);
            if (bundle == null) { completion.accept(error("Termux did not return a setup result.")); return; }
            int exit = bundle.getInt("exitCode", -1);
            String stdout = bundle.getString("stdout", "");
            String stderr = bundle.getString("stderr", "");
            String[] lines = stdout.split("\\r?\\n");
            for (int index = lines.length - 1; index >= 0; index--) {
                if (!lines[index].startsWith("VSTAB_TERMUX_RESULT\t")) continue;
                String[] fields = lines[index].split("\t", -1);
                if (fields.length != 5) break;
                try {
                    completion.accept(new Result(fields[1], decode(fields[2]), decode(fields[3]), fields[4], exit));
                    return;
                } catch (IllegalArgumentException malformed) { break; }
            }
            String message = bundle.getString("errmsg", "");
            if (message.isEmpty()) message = "Termux stopped before Linux setup completed. Check its external-command setting and available storage.";
            completion.accept(new Result("error", message, stderr, "", exit));
        }
    }
}
