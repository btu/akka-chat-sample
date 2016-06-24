package btu.chat.backend
import btu.chat.ChatApi._
import akka.actor._
import akka.cluster.sharding._
import scala.concurrent.duration._
import akka.persistence._
import scala.collection.JavaConversions._

//Internal Apis
case class NotifyParticipants(x: TopicMessage)
final object SnapshotAndDie
case class TopicDataSnapshotAck(week: String, messageId: Long)
case class LoadMessages(week: String)

//This trait is for testing purpose, we can then control the time we create test messages.
trait TimeAware {
  def now: Long
}

object TopicActor {
  final object Stop

  val defaultWeek = Option(TopicWeek.newBuilder.setCurrentWeek("").setMessageId(1).setTimestamp(0l).build)

  //protocol buffer object are rather verbose, we can make some converter to get those out of the way
  //We could probably also use some more scalafied library such as scalabuff 

  implicit def createTopic2Descriptor(x: ActionCreateTopic)(implicit descriptor: Option[TopicDescriptor], now: Long): Option[TopicDescriptor] = {
    val b = Option(TopicDescriptor.newBuilder
      .setName(x.getName)
      .setId(x.getTopicId)
      .setOwnerId(x.getSenderId)
      .addParticipant(x.getSenderId)
      .setCreateTimestamp(now))

    val bmod = for (b <- b; d <- descriptor) yield {
      b.clearParticipant.addAllParticipant(d.getParticipantList)
        .setCreateTimestamp(d.getCreateTimestamp)
    }
    bmod.orElse(b).map(_.build)
  }

  implicit def updateParticipant(x: ActionAddParticipant)(implicit descriptor: Option[TopicDescriptor]): Option[TopicDescriptor] =
    for (d <- descriptor) yield {
      var participants: Seq[String] =
        if (x.hasInvert && x.getInvert)
          d.getParticipantList.filterNot(_ == x.getParticipantId)
        else if (!d.getParticipantList.contains(x.getParticipantId))
          (d.getParticipantList :+ x.getParticipantId)
        else d.getParticipantList
      if (!participants.contains(d.getOwnerId)) participants :+= d.getOwnerId
      d.toBuilder.clearParticipant.addAllParticipant(participants).build
    }

  implicit def makeTextMessage(x: ActionPostText)(implicit now: Long): TopicMessage = {
    val b = TopicMessage.newBuilder
      .setMessageId(0)
      .setSenderId(x.getSenderId)
      .setMessageTimestamp(now)
      .setText(TopicMessageText.newBuilder.setData(x.getText).build)
    if (x.hasSenderTimestamp) b.setSenderTimestamp(x.getSenderTimestamp)
    b.build
  }
}
trait TopicActor extends PersistentActor with TimeAware with ActorLogging {
  import ShardRegion.Passivate
  import TopicActor._
  //topic-${topicId}
  val topicId = self.path.name
  override def persistenceId: String = s"topic-$topicId"

  //After 5 minute of inactivity, we will passivate (kill) this actor and its child to free memory 
  context.setReceiveTimeout(300.seconds)

  implicit var descriptor = Option.empty[TopicDescriptor]
  var currentWeek = Option.empty[TopicWeek]

  val weekFormat = new java.text.SimpleDateFormat("yyyyw")
  var weeks = Map[String, ActorRef]()
  var streams = Map[String, ActorRef]()

  def receiveRecover = {
    case x: TopicWeek => currentWeek = Option(x)
    case x: ActionCreateTopic =>
      implicit val serverTime = now
      descriptor = x
    case x: ActionAddParticipant => descriptor = x
  }

  def indexTopic: PartialFunction[Any, Unit] = {
    case _ =>
      log.info("Pretend {} is indexed", descriptor.map(_.getId))
    //TODO: index according descriptor (name,owner,participant ...)
    //This would help having feature such as finding topic by participant/owner, listing someone's topic ...
    //this can be done either with a persistant actor, or using a db.
  }

  def weekActor(wn: String): ActorRef = {
    weeks.get(wn) match {
      case Some(a) => a
      case _ =>
        val mid = currentWeek.filter(_.getCurrentWeek == wn).map(_.getMessageId).getOrElse(-1l)
        val wa = context.system.actorOf(Props(new TopicDataActor(topicId, wn, mid)))
        weeks += wn -> wa
        wa
    }
  }
  /**
   * Provide an actor for the current week.
   * in case the current week is not created yet, we must close the previous week and get the current message index.
   * switching weeks is one way to introduce an interesting relationship between parent and child actor, but also to avoid loading long history
   * returns:Option[ActorRef], If none, retry after a few seconds
   */
  def currentWeekActor: Option[ActorRef] = {
    val week = weekFormat.format(now)
    val r: Option[Option[ActorRef]] = for (tw <- currentWeek orElse defaultWeek) yield {
      log.info("Getting week actor for currentWeek={} <> week={}", tw.getCurrentWeek, week)
      if (week != tw.getCurrentWeek) {
        //we need to close a week
        if (tw.getCurrentWeek == "") self ! TopicDataSnapshotAck("", 1)
        else weekActor(tw.getCurrentWeek) ! SnapshotAndDie
        None
      } else {
        Option(weekActor(week))
      }
    }
    r.flatten
  }

  def receiveOwnerActions: Receive = {
    case x: ActionCreateTopic if (!x.hasTopicId || x.getTopicId == topicId) &&
      (Option(x.getSenderId) == descriptor.map(_.getOwnerId) || descriptor.isEmpty) =>
      val op: ActorRef = sender

      persistAsync(x.toBuilder.setTopicId(topicId).build)(receiveRecover andThen indexTopic andThen {
        //protocol buffer is cumbersome
        case _ => descriptor match {
          case Some(d) => op ! d
          case _ => op ! Errors.genericError(11, "failed to make topic").setRequestId(x.getRequestId).build
        }
      })
    case _ if descriptor == None =>
      sender ! Errors.genericError(2, "topic is not defined").setRequestId("-1").build

    case x: ActionAddParticipant if Option(x.getSenderId) == descriptor.map(_.getOwnerId) =>
      val op: ActorRef = sender
      persistAsync(x)(receiveRecover andThen {
        case _ => descriptor match {
          case Some(d) => op ! d
          case _ => op ! Errors.genericError(12, "failed to add participant to topic").setRequestId(x.getRequestId).build
        }
      })

  }
  def isMember(sid: String): Boolean = {
    val participant: Seq[String] = descriptor.toSeq.flatMap(_.getParticipantList.toSeq)
    participant.contains(sid)
  }

  def receiveMembersActions: Receive = {
    //Member check
    case x: ActionTopicQuery if !isMember(x.getSenderId) =>
      sender ! Errors.NotAMember(x.getRequestId)
    case x: ActionGetDescriptor if !isMember(x.getSenderId) =>
      sender ! Errors.NotAMember(x.getRequestId)
    case x: ActionPostText if !isMember(x.getSenderId) =>
      sender ! Errors.NotAMember(x.getRequestId)
    case x: ActionTopicQuery if x.hasKey && !streams.contains(x.getKey) =>
      sender ! Errors.StreamNotFound(x.getRequestId)

    case x: ActionTopicQuery if x.hasKey =>
      //We have a key so we just forward to the stream we opened earlier
      streams(x.getKey) forward x
    case x: ActionTopicQuery =>
      val aid = java.util.UUID.randomUUID.toString
      val a = context.actorOf(Props(new TopicStreamActor(now, descriptor.map(_.getCreateTimestamp).getOrElse(now))), aid)
      context.watch(a)
      a forward x

    case x: ActionGetDescriptor =>
      for (d <- descriptor)
        sender ! x

    //We should create a stream actor for this request
    case x: ActionPostText =>
      implicit val serverTime = now
      val m: TopicMessage = x
      log.debug("ActionPostText({},{}) from {}", x.getRequestId, x.getSenderId, sender())
      val s: ActorRef = sender
      currentWeekActor match {
        case Some(a) => a forward (x.getRequestId -> m)
        case _ =>
          //Actor is not available yet, we must retry later
          import context.dispatcher
          log.debug("week actor not found, schedule ActionPostText(sid={},rqid={})", m.getSenderId, x.getRequestId)
          context.system.scheduler.scheduleOnce(1.seconds, self, x)(context.dispatcher, s)
      }
  }

  def receiveCommand: Receive =
    receiveOwnerActions orElse
      receiveMembersActions orElse {

        case x @ LoadMessages(w) =>
          weekActor(w) forward x
        case NotifyParticipants(m) =>
          //Here we notify participant only. We could adapt logic to also notify other persons such as follower, or whatever else.
          log.info("Notification {} would be sent to {}", m.getMessageId, descriptor.map(_.getParticipantList.filterNot(_ == m.getSenderId)))
        case TopicDataSnapshotAck(week, mid) =>
          val week = weekFormat.format(now)
          for (tw <- currentWeek orElse defaultWeek) {
            log.info("SnapshotAck if {} == {}", week, tw.getCurrentWeek)
            if (week == tw.getCurrentWeek || tw.getCurrentWeek == "") {
              persist(TopicWeek.newBuilder
                .setCurrentWeek(week)
                .setTimestamp(now)
                .setMessageId(tw.getMessageId).build) { receiveRecover }
            }
          }
        case Terminated(x) =>
          // When a stream terminates, we unregister it.
          if (streams.contains(x.path.name)) streams -= x.path.name

        case ReceiveTimeout =>
          context.parent ! Passivate(stopMessage = Stop)
        case Stop => context.stop(self) //immediate termination, no message will be received after this one
        case _ => sender ! Errors.BadMessage("cannot handle request")
      }
}

/**
 * Holds the state for an open stream.
 * Since user can open any amount of stream they want, it is important that
 * this actor is killed in a timely fashion.
 *
 * TODO: replace the logic with akka stream by making TopicDataActor a publisher[TopicMessage] and TopicActor a publisher[TopicDataWeeks]
 */
class TopicStreamActor(now: Long, createTime: Long) extends Actor with ActorLogging {
  def id = self.path.name
  context.setReceiveTimeout(300.seconds)
  var currentWeek = ""
  var weekData = Seq[TopicMessage]()
  val weekFormat = new java.text.SimpleDateFormat("yyyyw")
  val calendar = java.util.Calendar.getInstance
  var requester = Option.empty[ActorRef]
  var request = Option.empty[ActionTopicQuery]
  def receive: Receive = {
    case x: ActionTopicQuery if requester.isDefined =>
      sender ! Errors.StreamInProcess(x.getRequestId)
    case x: ActionTopicQuery if !x.hasKey =>
      log.debug("Request message for week {}", currentWeek)
      requester = Option(sender())
      request = Some(x)
      requestMessages()

    case x: ActionTopicQuery =>
      requester = Option(sender())
      request = Some(x)
      processRequestor()
    case x: Seq[_] =>
      weekData = x.flatMap { case x: TopicMessage => Some(x) case _ => None }
      processRequestor()
  }
  def requestMessages(): Unit = for (x <- request) {
    calendar.setTimeInMillis(now)
    if (x.hasTimeframe && x.getTimeframe.hasEnd) {
      calendar.setTimeInMillis(x.getTimeframe.getEnd)
    }
    currentWeek = weekFormat.format(calendar.getTime)
    log.debug("Request message for week {}", currentWeek)
    context.parent ! LoadMessages(currentWeek)
  }
  def processRequestor(): Unit = for (x <- request) {
    //We want to get at least 1 message
    if (weekData.size == 0) {
      if ((x.hasTimeframe && x.getTimeframe.getStart > calendar.getTimeInMillis) ||
        createTime > calendar.getTimeInMillis) {
        for (s <- requester) s ! TopicData.newBuilder.setRequestId(x.getRequestId).build
        requester = None
      } else {
        calendar.add(java.util.Calendar.WEEK_OF_YEAR, -1)
        requestMessages()
      }
    } else {
      val (h, t) = if (x.hasMaxMessages) weekData.splitAt(x.getMaxMessages) else (weekData, Seq())
      weekData = t
      for (s <- requester) s ! TopicData.newBuilder.setRequestId(x.getRequestId).addAllMessages(h).setKey(id).build
      requester = None
    }
  }
}

import akka.persistence._
/**
 * Actor data is responsible for holding and managing topic data.
 * it is broken down by week in order to avoid having to load up
 * all historical data each time a chat is posted.
 * It would also make easier to drop past data, or archive it.
 * It is created directly by the topic actor and therefore reside in the same node.
 *
 * Note: We could also add a cluster sharding router to distribute load, but
 * it doesn't seems appropriate in this case.
 */
class TopicDataActor(topicId: String, weekCode: String, var messageId: Long) extends PersistentActor with ActorLogging //with ActorPublisher[TopicMessage] 
{
  //The unique topicId
  override def persistenceId: String = s"topic-$topicId-$weekCode"

  //Here we will append message, Seq make a list, which has linear append time.
  //We therefore prefer an append optimized collection for this case.
  var messages = scala.collection.mutable.ListBuffer[TopicMessage]()

  override def receiveRecover: Receive = {
    case x: TopicMessage =>
      messages += x
      messageId += 1
  }

  def notifyParticipant(m: TopicMessage): Receive = {
    case _ => context.parent ! NotifyParticipants(m)
  }

  override def receiveCommand: Receive = {
    case LoadMessages(w) =>
      sender ! messages.toSeq
    case SnapshotAndDie =>
      //TODO do the snapshot to compact stored message and recovery
      sender ! TopicDataSnapshotAck(weekCode, messageId)
    case (rqid: String, x: TopicMessage) =>

      val m = x.toBuilder.setMessageId(messageId).build
      //If Receive was not defined as PartialFunction[Any,Unit] we could have it return the object we need and chain as we like. It is not the case however
      val s: ActorRef = sender
      persistAsync(m)(receiveRecover andThen notifyParticipant(m) andThen {
        case _ =>
          log.debug("Saved message {} from {}", m.getMessageId, s)
          s ! m //ChatAck.newBuilder.setRequestId(rqid).setTimestamp(x.getMessageTimestamp).build
      })
    case _ => sender ! Errors.BadMessage("")
  }
}