package btu.chat.backend

import btu.chat.ChatApi._
import akka.actor._
import akka.cluster.client.ClusterClientReceptionist
import akka.cluster.sharding._

//Actual topic actor
class DefaultTopicActor extends TopicActor {
  def now = System.currentTimeMillis
}

//Here using clusterClientReceptionist, We do not need to use it, service could also run in the same cluster for example
class ChatService extends Actor with ActorLogging {

  override def preStart(): Unit = {
    ClusterClientReceptionist(context.system).registerService(self)
  }

  val extractEntityId: ShardRegion.ExtractEntityId = {
    case (x: String, y) => (x, y)
  }
  val extractShardId: ShardRegion.ExtractShardId = {
    case (x: String, y) => math.abs((x.hashCode % 44)).toString
  }
  val topicShard = ClusterSharding(context.system)
    .start(
      typeName = "ChatTopics",
      entityProps = Props[DefaultTopicActor],
      settings = ClusterShardingSettings(context.system),
      extractEntityId,
      extractShardId
    )
  def receive: Receive = {
    // We create a new topic
    case x: ActionCreateTopic if !x.hasTopicId =>
      val nid = java.util.UUID.randomUUID.toString
      topicShard forward (nid -> x.toBuilder.setTopicId(nid).build)
    // We forward to the right shard
    case x: ActionCreateTopic => topicShard forward (x.getTopicId -> x)
    case x: ActionAddParticipant => topicShard forward (x.getTopicId -> x)
    case x: ActionPostText => topicShard forward (x.getTopicId -> x)
    case x: ActionTopicQuery => topicShard forward (x.getTopicId -> x)
    case _ => sender ! Errors.BadMessage("")
  }
}

