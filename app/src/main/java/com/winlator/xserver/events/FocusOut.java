package com.winlator.xserver.events;

import com.winlator.xserver.Window;

/** X11 FocusOut (event code 10). */
public class FocusOut extends FocusChange {
    public FocusOut(Window event, Detail detail, Mode mode) {
        super(10, event, detail, mode);
    }
}
