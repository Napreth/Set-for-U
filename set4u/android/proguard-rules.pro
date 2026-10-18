# Shizuku loads the UserService and its Context constructor by reflection.
-keep class com.napreth.set4u.bridge.BridgeUserService {
    public <init>(android.content.Context);
}