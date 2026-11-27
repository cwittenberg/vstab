package app.vstab;

import android.content.Context;
import android.net.Uri;
import android.system.Os;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

/** Private-key bytes stay on the native side and retain their original encryption. */
final class NativeSshKeys {
    private NativeSshKeys() { }

    static boolean isEncrypted(Context context, Uri source) throws Exception {
        byte[] bytes;
        try (InputStream input = context.getContentResolver().openInputStream(source)) {
            if (input == null) throw new IOException("The selected IdentityFile could not be opened.");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > 1024 * 1024) throw new IOException("SSH private keys must be smaller than 1 MiB.");
                output.write(buffer, 0, count);
            }
            bytes = output.toByteArray();
        }
        com.jcraft.jsch.KeyPair parsed = null;
        try {
            parsed = com.jcraft.jsch.KeyPair.load(new com.jcraft.jsch.JSch(), bytes, null);
            return parsed.isEncrypted();
        } finally { if (parsed != null) parsed.dispose(); Arrays.fill(bytes, (byte) 0); }
    }

    static String importKey(Context context, Uri source) throws Exception {
        File home = new File(context.getFilesDir(), "home").getCanonicalFile();
        File directory = new File(home, ".ssh/keys").getCanonicalFile();
        if (!directory.toPath().startsWith(home.toPath())) throw new IOException("The private SSH key directory must stay inside the Android home.");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("The private SSH key directory could not be created.");
        Os.chmod(directory.getParent(), 0700); Os.chmod(directory.getAbsolutePath(), 0700);
        byte[] key;
        try (InputStream input = context.getContentResolver().openInputStream(source)) {
            if (input == null) throw new IOException("The selected key could not be opened.");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > 1024 * 1024) throw new IOException("SSH private keys must be smaller than 1 MiB.");
                output.write(buffer, 0, count);
            }
            key = output.toByteArray();
        }
        File destination = new File(directory, UUID.randomUUID() + ".key");
        try {
            String header = new String(key, 0, Math.min(key.length, 120), StandardCharsets.US_ASCII);
            if (!header.matches("(?s)^-----BEGIN (OPENSSH |RSA |EC |DSA |ENCRYPTED )?PRIVATE KEY-----.*"))
                throw new IOException("Choose an OpenSSH or PEM private key file.");
            if (!destination.createNewFile()) throw new IOException("A private key filename could not be allocated.");
            try (FileOutputStream output = new FileOutputStream(destination)) {
                Os.chmod(destination.getAbsolutePath(), 0600);
                output.write(key); output.getFD().sync();
            }
            return "~/.ssh/keys/" + destination.getName();
        } catch (Exception error) {
            if (destination.exists()) destination.delete();
            throw error;
        } finally { Arrays.fill(key, (byte) 0); }
    }

    static Uri resolve(Context context, String requested, SshConfigStore.Host host) throws IOException {
        String home = new File(context.getFilesDir(), "home").getCanonicalPath();
        // SshConfigStore has already expanded OpenSSH tokens. Expanding again
        // would corrupt literal percent sequences originally written as %%.
        String path = requested;
        if (path.equals("~")) path = home;
        else if (path.startsWith("~/")) path = home + path.substring(1);
        else if (!path.startsWith("/")) path = home + "/" + path;
        File key = new File(path).getCanonicalFile();
        if (!key.isFile() || !key.canRead() || key.length() > 1024 * 1024)
            throw new IOException("The configured IdentityFile is unavailable. Import a key or correct its path in SSH config.");
        return Uri.fromFile(key);
    }
}
