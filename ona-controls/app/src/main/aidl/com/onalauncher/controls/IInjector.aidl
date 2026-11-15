// Runs inside the Shizuku user service (shell uid), see shizuku/InjectorService.kt.
package com.onalauncher.controls;

interface IInjector {
    // Called by the Shizuku server when the service is removed.
    void destroy() = 16777114;
    void exit() = 1;
    // One key event to the focused window, like a hardware keyboard. Returns false if Android refused it.
    boolean key(int code, boolean down, int meta, long downTime) = 2;
    // Which injection API was found ("InputManagerGlobal" / "InputManager" / error text).
    String probe() = 3;
}
