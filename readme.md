# A Simple Redis Client

```scala
val client = redicl.default.Redis("localhost", 6379)

// Direct interaction, just like redis-cli. Works great in the REPL!
client.exec("SET", "a", 42)
// val res1: redicl.RespValue = SimpleString(OK)

client.exec("GET", "a")
// val res2: redicl.RespValue = ArrayBulkString(42)

// Method wrappers for common commands
client.set("b", "1000")
client.get("b")
// val res4: Option[redicl.RespValue.BulkString] = Some(ArrayBulkString(1000))

// Wrappers for typed access
client.get[String]("b")
// val res5: Option[String] = Some(1000)
client.get[Int]("b")
// val res6: Option[Int] = Some(1000)
```

## Status

- The core client code is implemented, allowing you to call any Redis command
  and interpreting the results as you wish.

- Custom typed serializers and deserializers can be defined by the user.

- The selection of method wrappers around known commands is limited. New ones
  can easily be added however, and contributions are welcome.
