// Privileged storage user-service contract.
//
// The interface is served only from a process started by Shizuku and therefore
// must stay minimal and stable: every method returns a JSON string so the
// binder surface never changes shape. `destroy` keeps the reserved transaction
// id used by the Shizuku user-service lifecycle.
//
// All call methods carry explicit transaction ids so the interface never mixes
// implicit and explicit ids (the AIDL compiler rejects that mix). The ids are
// stable and must not be reordered: inspect=0, prepare=1, start=2, status=3,
// migrationInfo=4, prepareMigration=5, startMigration=6, migrationStatus=7,
// and the mandatory Shizuku lifecycle destroy=16777114.
package io.github.mydanyi.armadatf;

interface IStorageService {
    // Read-only disk/volume/application inspection.
    String inspect() = 0;

    // Destructive whole-card partition flow (prepare -> start -> status).
    String prepare(String diskId, int linuxPercent) = 1;
    String start(String token, boolean confirmed) = 2;
    String status() = 3;

    // Read-only live card/migration inspection for one disk.
    String migrationInfo(String diskId) = 4;

    // Platform-native primary-storage/application migration flow
    // (prepare -> start -> migrationStatus). Prepare is read-only and captures
    // a one-use request token; start consumes it after explicit confirmation.
    String prepareMigration(String requestJson) = 5;
    String startMigration(String token, boolean confirmed) = 6;
    String migrationStatus(int moveId) = 7;

    void destroy() = 16777114;
}
