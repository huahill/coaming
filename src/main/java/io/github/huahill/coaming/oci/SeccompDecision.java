package io.github.huahill.coaming.oci;

public final class SeccompDecision {
    public enum Result { SKIP, ADD, FAIL }

    private SeccompDecision() {}

    public static Result decide(int resolvedNumber, int addResult) {
        if (resolvedNumber < 0) {
            return Result.SKIP;
        }
        return addResult == 0 ? Result.ADD : Result.FAIL;
    }
}
