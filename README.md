# JROSX
A Java-Based robotics middleware framework compatible with ROS2.

## Usage
### CLI

```bash
# Subscribe to a topic
jrosx topic echo /chatter

# Publish to a topic
jrosx topic pub /chatter std_msgs/msg/String "data: 'hello'"

# Start a service server
jrosx service server /add_two_ints example_interfaces/srv/AddTwoInts

# Call a service
jrosx service call /add_two_ints example_interfaces/srv/AddTwoInts "{a: 1, b: 2}"
```

### Make Node

```java
import jrosx.Node;
import jrosx.Publisher;
import std_msgs.msg.String_;

public class HelloWorldPublisher {
  public static void main(String[] args) throws Exception {
      try (Node node = new Node("hello_world_publisher");
          Publisher<String_> publisher = node.createPublisher("/chatter", String_.class);

          Thread.sleep(500);

          while (!Thread.currentThread().isInterrupted()) {
              publisher.publish(new String_("Hello World"));
              System.out.println("Published: Hello World");
              Thread.sleep(1000);
          }
      }
  }
}
```


## LICENSE

[Apache License 2.0](https://github.com/niyarin/ddsj/blob/main/LICENSE)
