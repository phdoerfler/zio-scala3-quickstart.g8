package $package$

import zhttp.http._
import zhttp.service._
import zhttp.service.server.ServerChannelFactory
import zio._
import zio.console._
import $package$.repo.itemrepository.ItemRepo
import zio.random.Random
import $package$.service.itemservice.BusinessLogic
import $package$.service.itemservice.BusinessLogic._
import $package$.domain.DomainError
import scala.util.Try

object Main extends zio.App:

  //TODO move to config with zio-config
  private val port = 8080
  private val repoLayer = (Random.live ++ Console.live) >>> ItemRepo.live
  private val businessLayer = repoLayer >>> BusinessLogic.live
  private val applicationLayer = businessLayer ++ ServerChannelFactory.auto

  def run(args: List[String]): URIO[ZEnv, ExitCode] =
    val nThreads: Int = args.headOption.flatMap(x => Try(x.toInt).toOption).getOrElse(0)

    server
      .make
      .use(_ => console.putStrLn(s"Server started on port \$port") *> ZIO.never)
      .provideCustomLayer(applicationLayer ++ EventLoopGroup.auto(nThreads))
      .exitCode

  val healthCheck: HttpApp[Any, Nothing] = HttpApp.collect {
    case Method.GET -> Root / "health" =>
      Response.status(Status.OK)
  }

  // TODO serialize with zio json
  val app: HttpApp[BusinessLogic, Throwable] = HttpApp.collectM {
    case Method.GET -> Root / "items" =>
      getAllItems().map(items => Response.text(items.mkString(", ")))

    case Method.GET -> Root / "item" / id =>
      getItemById(id)
        .some
        .mapError {
          case Some(exception) => exception
          case None            => new java.lang.RuntimeException(s"Item with \$id does not exists")
        }
        .map(item =>
          Response.jsonString(s"""{"id": "\$id", "description": "\${item.description}"}""")
        )

    case Method.DELETE -> Root / "item" / id =>
      deleteItem(id).map(_ => Response.ok)

    case req @ Method.POST -> Root / "item" =>
      (for {
        body <- ZIO
          .fromOption(req.getBodyAsString.flatMap(b => if b.isEmpty then None else Some(b)))
          .absorbWith(_ => new IllegalArgumentException(s"Request body was empty"))
        id <- addItem(body)
      //TODO add response status 201 "created"
      } yield id).map(itemId => Response.jsonString(s"""{"id": "\${itemId.value}"}"""))

    //TODO deserialize json body to Item
    case req @ Method.POST -> Root / "item/update" => // updateItem
      ZIO.succeed(Response.ok)

    //TODO create request handler that delegate to getItemsByIds()
  }

  val server: Server[BusinessLogic, Throwable] =
    Server.port(port) ++
      Server.app(healthCheck +++ app)