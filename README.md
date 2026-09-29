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


## LICENSE

[Apache License 2.0](https://github.com/niyarin/ddsj/blob/main/LICENSE)
