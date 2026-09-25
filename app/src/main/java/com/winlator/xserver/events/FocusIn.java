package com.winlator.xserver.events;

import com.winlator.xserver.Window;

/** X11 FocusIn (event code 9). */
public class FocusIn extends FocusChange {
    public FocusIn(Window event, Detail detail, Mode mode) {
        super(9, event, detail, mode);
    }
}
