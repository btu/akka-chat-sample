package btu.chat.backend

import btu.chat.ChatApi._

import akka.actor.ActorSystem
import akka.actor.Actor
import akka.actor.Props
import akka.testkit.{ TestActors, TestActorRef, TestKit, ImplicitSender }
import org.scalatest.WordSpecLike
import org.scalatest.Matchers
import org.scalatest.BeforeAndAfterAll

object TestTopicActor {
  val ctime = java.util.Calendar.getInstance
}
class TestTopicActor extends TopicActor {
  def now = TestTopicActor.ctime.getTimeInMillis
}

class TopicApiSpec extends TestKit(ActorSystem("ApiSpec"))
    with ImplicitSender
    with WordSpecLike
    with Matchers
    with BeforeAndAfterAll {

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  val topicIdA = "test1"
  val topicName = "TestTopic"
  val topicOwner = "testuser1"
  val topicOtherOwner = "testuser3"
  val topicParticipant = "testuser2"
  var rqid = 1
  var createTime = 0L

  "A Topic actor" must {
    val topic = system.actorOf(Props[TestTopicActor], topicIdA)
    "Allow creating a topic when id does NOT exists" in {
      rqid += 1
      topic ! ActionCreateTopic.newBuilder.
        setRequestId(rqid.toString).
        setName(topicName).
        setSenderId(topicOwner).build
      val td = expectMsgType[TopicDescriptor]
      td.getId should be(topicIdA)
      td.getName should be(topicName)
      td.getOwnerId should be(topicOwner)
      createTime = TestTopicActor.ctime.getTimeInMillis
      td.getCreateTimestamp should be(createTime)
    }

    "Reject creating a topic when id does exists" in {
      rqid += 1
      topic ! ActionCreateTopic.newBuilder.
        setRequestId(rqid.toString).
        setName(topicName).
        setSenderId(topicOtherOwner).build
      val td = expectMsgType[ChatError]
      td.getErrorId should be(20001)
    }

    "Allow updating a topic name by original owner" in {
      rqid += 1
      topic ! ActionCreateTopic.newBuilder.
        setRequestId(rqid.toString).
        setName(topicName.toUpperCase).
        setSenderId(topicOwner).build
      val td = expectMsgType[TopicDescriptor]
      td.getId should be(topicIdA)
      td.getName should be(topicName.toUpperCase)
      td.getOwnerId should be(topicOwner)
      td.getParticipantList.size should be(1)
      td.getParticipantList should contain(topicOwner)
    }

    "accept adding participant" in {
      rqid += 1
      topic ! ActionAddParticipant.newBuilder
        .setRequestId(rqid.toString)
        .setTopicId(topicIdA)
        .setParticipantId(topicParticipant)
        .setSenderId(topicOwner).build
      val td = expectMsgType[TopicDescriptor]
      td.getId should be(topicIdA)
      td.getName should be(topicName.toUpperCase)
      td.getOwnerId should be(topicOwner)
      td.getParticipantList should contain(topicParticipant)
      td.getParticipantList should contain(topicOwner)
      td.getParticipantList.size should be(2)
    }

    "Keep participant unique" in {
      TestTopicActor.ctime.add(java.util.Calendar.MINUTE, 1)
      rqid += 1
      topic ! ActionAddParticipant.newBuilder
        .setRequestId(rqid.toString)
        .setTopicId(topicIdA)
        .setParticipantId(topicParticipant)
        .setSenderId(topicOwner).build
      var td = expectMsgType[TopicDescriptor]
      td.getId should be(topicIdA)
      td.getName should be(topicName.toUpperCase)
      td.getOwnerId should be(topicOwner)
      td.getParticipantList should contain(topicParticipant)
      td.getParticipantList should contain(topicOwner)
      td.getParticipantList.size should be(2)
      td.getCreateTimestamp should be(createTime)

      rqid += 1
      topic ! ActionAddParticipant.newBuilder
        .setRequestId(rqid.toString)
        .setTopicId(topicIdA)
        .setParticipantId(topicOwner)
        .setSenderId(topicOwner).build
      td = expectMsgType[TopicDescriptor]
      td.getId should be(topicIdA)
      td.getName should be(topicName.toUpperCase)
      td.getOwnerId should be(topicOwner)
      td.getParticipantList should contain(topicParticipant)
      td.getParticipantList should contain(topicOwner)
      td.getParticipantList.size should be(2)
    }

    // "Allow removing a participant" in fail

    "Post message" in {
      rqid += 1
      topic ! ActionPostText.newBuilder
        .setRequestId(rqid.toString)
        .setTopicId(topicIdA)
        .setSenderId(topicOwner)
        .setText("Hello text 1").build
      val tm = expectMsgType[TopicMessage]
    }
    "Retrieve message" in {
      rqid += 1
      topic ! ActionTopicQuery.newBuilder
        .setRequestId(rqid.toString)
        .setTopicId(topicIdA)
        .setSenderId(topicOwner).build
      val r = expectMsgType[TopicData]
      r.getRequestId should be(rqid.toString)
      r.hasKey should be(true)
      r.getMessagesList.size should be(1)
      r.getMessages(0).getSenderId should be(topicOwner)
      r.getMessages(0).getText.getData should be("Hello text 1")
    }

    // "Reject posting in the past" in fail

    // "Handle changing weeks" in {
    //   //now + two weeks
    //   fail
    // }

  }
}