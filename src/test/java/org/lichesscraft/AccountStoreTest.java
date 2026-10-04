package org.lichesscraft;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class AccountStoreTest {
    @TempDir Path directory;
    @Test void encryptionAndRestart() throws Exception {
        UUID id=UUID.randomUUID();var account=new AccountStore.Account("TestPlayer","TEST_TOKEN_NOT_REAL",System.currentTimeMillis()+60_000);var store=new AccountStore(directory);store.put(id,account);
        assertEquals(account,new AccountStore(directory).get(id));String ciphertext=new String(Files.readAllBytes(directory.resolve("accounts.enc")),java.nio.charset.StandardCharsets.ISO_8859_1);assertFalse(ciphertext.contains(account.token()));assertFalse(ciphertext.contains(account.username()));
    }
    @Test void removalPersists() throws Exception {
        UUID id=UUID.randomUUID();var store=new AccountStore(directory);store.put(id,new AccountStore.Account("Test","fake",System.currentTimeMillis()+60_000));store.remove(id);assertNull(new AccountStore(directory).get(id));
    }
    @Test void expiredTokenIsNotUsed() throws Exception {
        UUID id=UUID.randomUUID();var store=new AccountStore(directory);store.put(id,new AccountStore.Account("Test","fake",0));assertNull(store.get(id));
    }
    @Test void missingKeyDoesNotSilentlyEraseAccounts() throws Exception {
        var store=new AccountStore(directory);store.put(UUID.randomUUID(),new AccountStore.Account("Test","fake",Long.MAX_VALUE));Files.delete(directory.resolve("server.key"));assertThrows(IllegalStateException.class,()->new AccountStore(directory));assertTrue(Files.exists(directory.resolve("accounts.enc")));
    }
    @Test void tamperingDetected() throws Exception {
        var store=new AccountStore(directory);store.put(UUID.randomUUID(),new AccountStore.Account("Test","fake",Long.MAX_VALUE));Path file=directory.resolve("accounts.enc");byte[] bytes=Files.readAllBytes(file);bytes[bytes.length-1]^=1;Files.write(file,bytes);assertThrows(Exception.class,()->new AccountStore(directory));
    }
}
