import utest._

object RediclTest extends TestSuite:

  def withClient(action: redicl.default.Redis => Unit) =
    val client = redicl.default.Redis("127.0.0.1")
    try
      action(client)
    finally
      client.close()

  val tests = Tests{
    test("main"){
      withClient{ client =>
        client.exec("SET", "a", "42")
        assert(client.exec("GET", "a").str == "42")
      }
    }
  }

