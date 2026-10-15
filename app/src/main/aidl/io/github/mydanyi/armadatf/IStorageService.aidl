// Privileged storage user-service contract.
//
// The interface is served only from a process started by Shizuku and therefore
// must stay minimal and stable: every method returns a JSON string so the
// binder surface never changes shape. `destroy` keeps the reserved transaction
// id used by the Shizuku user-service lifecycle.
//
// All four call methods carry explicit transaction ids so the interface never
// mixes implicit and explicit ids (the AIDL compiler rejects that mix). The
// ids are stable and must not be reordered: inspect=0, prepare=1, start=2,
// status=3, and the mandatory Shizuku lifecycle destroy=16777114.
package io.github.mydanyi.armadatf;

interface IStorageService {
    String inspect() = 0;
    String prepare(String diskId, int linuxPercent) = 1;
    String start(String token, boolean confirmed) = 2;
    String status() = 3;
    void destroy() = 16777114;
}
