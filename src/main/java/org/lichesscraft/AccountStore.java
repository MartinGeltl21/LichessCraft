package org.lichesscraft;

import com.google.gson.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.*;

/** Encrypted at rest; key stays on the server and must be protected with its backups. */
public final class AccountStore {
    public record Account(String username, String token, long expiresAt) {}
    private final Path file;
    private final SecretKeySpec key;
    private final Map<UUID,Account> accounts=new HashMap<>();
    public AccountStore(Path directory) throws Exception {
        Files.createDirectories(directory);
        privatePermissions(directory, true);
        file=directory.resolve("accounts.enc");
        Path keyFile=directory.resolve("server.key");
        if (!Files.exists(keyFile)) {
            if (Files.exists(file)) throw new IllegalStateException("server.key fehlt; verschlüsselte Accounts können nicht gelesen werden.");
            byte[] bytes=new byte[32]; new SecureRandom().nextBytes(bytes); Files.write(keyFile,bytes,StandardOpenOption.CREATE_NEW);
        }
        privatePermissions(keyFile,false);
        key=new SecretKeySpec(Files.readAllBytes(keyFile),"AES");
        if (Files.exists(file)) {
            byte[] payload=Files.readAllBytes(file);
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,Arrays.copyOfRange(payload,0,12)));
            JsonObject json=JsonParser.parseString(new String(cipher.doFinal(Arrays.copyOfRange(payload,12,payload.length)),java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            for (var entry:json.entrySet()) accounts.put(UUID.fromString(entry.getKey()),new Gson().fromJson(entry.getValue(),Account.class));
            privatePermissions(file,false);
        }
    }
    public synchronized Account get(UUID id) {
        Account account=accounts.get(id);
        return account!=null && account.expiresAt()>System.currentTimeMillis() ? account : null;
    }
    public synchronized void put(UUID id,Account account) throws Exception {
        Account previous=accounts.put(id,account);
        try { save(); } catch(Exception e) { if(previous==null) accounts.remove(id); else accounts.put(id,previous); throw e; }
    }
    public synchronized void remove(UUID id) throws Exception {
        Account previous=accounts.remove(id);
        try { save(); } catch(Exception e) { if(previous!=null) accounts.put(id,previous); throw e; }
    }
    private void save() throws Exception {
        byte[] iv=new byte[12]; new SecureRandom().nextBytes(iv);
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE,key,new GCMParameterSpec(128,iv));
        JsonObject json=new JsonObject(); accounts.forEach((id,account)->json.add(id.toString(),new Gson().toJsonTree(account)));
        byte[] encrypted=cipher.doFinal(json.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] payload=new byte[12+encrypted.length]; System.arraycopy(iv,0,payload,0,12); System.arraycopy(encrypted,0,payload,12,encrypted.length);
        Path temporary=file.resolveSibling("accounts.enc.tmp"); Files.write(temporary,payload); privatePermissions(temporary,false);
        try { Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
        catch(AtomicMoveNotSupportedException e) { Files.move(temporary,file,StandardCopyOption.REPLACE_EXISTING); }
    }
    private static void privatePermissions(Path path,boolean directory) throws Exception {
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) Files.setPosixFilePermissions(path,PosixFilePermissions.fromString(directory?"rwx------":"rw-------"));
    }
}
