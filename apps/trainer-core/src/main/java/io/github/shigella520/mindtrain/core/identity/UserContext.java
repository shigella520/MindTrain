package io.github.shigella520.mindtrain.core.identity;

public final class UserContext {
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private UserContext() {}

    public static void set(String userId) {
        CURRENT.set(userId);
    }

    public static String requireUserId() {
        String userId = CURRENT.get();
        if (userId == null || userId.isBlank()) {
            throw new IllegalStateException("authenticated user is missing");
        }
        return userId;
    }

    public static void clear() {
        CURRENT.remove();
    }
}
