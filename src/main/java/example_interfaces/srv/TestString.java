package example_interfaces.srv;

import ddsj.dds.core.TypeSupport;
import jrosx.Ros2TypeSupport;

/**
 * Test: String-like message in srv namespace.
 */
public record TestString(String data) {
    public static final TypeSupport<TestString> TYPE_SUPPORT =
            Ros2TypeSupport.of(TestString.class, "example_interfaces/srv/TestString");
}
