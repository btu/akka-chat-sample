package btu.chat.backend

import akka.actor._
import akka.cluster.Cluster
object Main {
  def main(args: Array[String]): Unit = {

    val system = ActorSystem("chat-backend")
    Cluster(system)
    val a = system.actorOf(Props[ChatService], "chat-service")
  }

}

// case class Api[RQ, RS](service: String, name: String, desc: Option[String], canBuildFrom: Function)
// trait ApiDef {
//   def enumerate: Seq[Api]
// }
// object ChatApiDef extends ApiDef {

// }
