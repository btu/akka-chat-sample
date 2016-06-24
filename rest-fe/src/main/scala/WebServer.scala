package btu.frontend

import akka.actor._

import akka.stream.ActorMaterializer
import akka.pattern.ask

import akka.cluster.client.{ ClusterClient, ClusterClientSettings }

import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.marshalling._
import akka.http.scaladsl.model.MediaTypes._
import akka.http.scaladsl.server._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.unmarshalling._

import com.google.protobuf.GeneratedMessage
import scala.concurrent.duration._

object WebServer {

  // val jsonMarshaller = Marshaller.withFixedContentType(`application/json`) { obj =>
  //   HttpEntity(`application/json`, obj.toJson.compactPrint)
  // }

  // val mc2 = Marshaller.oneOf(
  //   // jsonApiMarshaller,
  //   jsonMarshaller
  // )

  val mapper = {
    import com.fasterxml.jackson.databind.{ SerializationFeature, DeserializationFeature, ObjectMapper, MapperFeature }
    val m = new ObjectMapper();
    m.registerModule(new com.hubspot.jackson.datatype.protobuf.ProtobufModule());
    // m.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    // m.configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES, true);
    // m.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false)
    m.enable(SerializationFeature.INDENT_OUTPUT)
    m
  }

  implicit val jsonMarshaller: ToEntityMarshaller[GeneratedMessage] =
    Marshaller.StringMarshaller.wrap(`application/json`) { x =>
      mapper.writeValueAsString(x)
    }
  implicit def jsonUnMarshaller[T <: GeneratedMessage](implicit m: Manifest[T]): FromEntityUnmarshaller[T] =
    Unmarshaller.byteStringUnmarshaller.forContentTypes(`application/json`).mapWithCharset { (data, charset) ⇒
      val input =
        if (charset == HttpCharsets.`UTF-8`) data.utf8String
        else data.decodeString(charset.nioCharset.name)
      mapper.readValue[T](input, m.runtimeClass.asInstanceOf[Class[T]])
    }

  def main(args: Array[String]) {

    implicit val system = ActorSystem("frontend")
    implicit val materializer = ActorMaterializer()
    // needed for the future flatMap/onComplete in the end
    implicit val executionContext = system.dispatcher
    implicit val requestTimeout = akka.util.Timeout(10.seconds)

    //Initial contacts from the config
    val clusterClient = system.actorOf(ClusterClient.props(ClusterClientSettings(system)))
    def chat(x: GeneratedMessage): scala.concurrent.Future[GeneratedMessage] = {
      (clusterClient ? ClusterClient.Send("/user/chat-service", x, localAffinity = true)).mapTo[GeneratedMessage]
    }
    import btu.chat.ChatApi._
    val route: Route =
      // Here the client gives us his id, in production we would integrate the front 
      // end with an authentication service to have a reliable & internal client id
      (pathPrefix("api" / "chat") & parameters('senderId, 'requestId)) { (sid, rid) =>
        path("create") {
          //example curl "http://localhost:8080/api/chat/create?senderId=blaise&requestId=1&name=test&participantId=toto"
          (get & parameters('name, 'participantId)) { (name, participantId) =>
            //Here we join two api in one
            val create = chat(ActionCreateTopic.newBuilder
              .setRequestId(rid)
              .setSenderId(sid)
              .setName(name)
              .build)
            val addParticipant = create.flatMap {
              case x: TopicDescriptor => chat(
                ActionAddParticipant.newBuilder
                .setRequestId(rid)
                .setSenderId(sid)
                .setTopicId(x.getId)
                .setParticipantId(participantId)
                .build
              )
              case x => scala.concurrent.Future { x }
            }
            complete(addParticipant.mapTo[GeneratedMessage])
          }
        } ~ (path(Segment / "descriptor") & get) { tid =>
          complete(chat(ActionGetDescriptor.newBuilder
            .setRequestId(rid)
            .setSenderId(sid)
            .setTopicId(tid).build))
        } ~ (path(Segment / "postText") & put & entity(as[String])) { (tid, data) =>
          complete(chat(ActionPostText.newBuilder
            .setRequestId(rid)
            .setSenderId(sid)
            .setTopicId(tid)
            .setText(data)
            .build))
        }
      } ~ pathPrefix("api" / "chat") {
        (path(Segment / "postText") & put & entity(as[ActionPostText])) { (tid, data) =>
          // shows json to protobuf unmarshalling
          // example:curl -X PUT -H "Content-type: application/json" "http://localhost:8080/api/chat/5470d419-20bc-4c2c-8595-caca75b23a48/postText" -d '{"requestid":"1","topicid":"227f73a7-f965-4a9c-9b10-ca83cb0e40e4","senderid":"blaise","text":"testMessage 1 !"}'
          complete(chat(data.toBuilder.setTopicId(tid).build))
        } ~
          (path(Segment / "query") & put & entity(as[ActionTopicQuery])) { (tid, data) =>
            // example:curl -X PUT -H "Content-type: application/json" "http://localhost:8080/api/chat/5470d419-20bc-4c2c-8595-caca75b23a48/query" -d '{"requestid":"1","topicid":"227f73a7-f965-4a9c-9b10-ca83cb0e40e4","senderid":"blaise"}'
            complete(chat(data.toBuilder.setTopicId(tid).build))
          }
      }

    val bindingFuture = Http().bindAndHandle(route, "localhost", 8080)

  }
}