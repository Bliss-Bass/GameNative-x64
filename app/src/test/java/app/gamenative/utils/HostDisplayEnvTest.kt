package app.gamenative.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class HostDisplayEnvTest {
  @Test
  fun x11UnixSocketPath_appendsX0UnderTmp() {
    val path = HostDisplayEnv.x11UnixSocketPath("/data/user/0/app/files/imagefs/tmp")
    assertEquals("/data/user/0/app/files/imagefs/tmp/.X11-unix/X0", path)
  }
}
