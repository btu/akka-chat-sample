Akkasample 
===========

This is a sample.

The goal is to build a simple chat service with akka in a cluster deployment.

The system is built as follow : 

Apis
-----

Api is defined for the service in a specific project ("chat-api"). it contains 
protocol buffer definition and generated messages.(following requestor's 
suggestion)

Api messages are separated in 3 kinds : 

Storage specific message (TopicDescriptor, TopicWeek, TopicDataSnapshot,
TopicMessage). Those are specifically designed to be store in the persistent 
actor, but may also be returned to user.

Responses are message that will not be stored, and are exclusively returned to 
user. It includes TopicData (the response to a query), ChatAck and ChatError.

Actions are message that represent a user action. These are the only messages 
the client can send. Those may be stored in the journal for persistence after 
being validated and approved.

Backend
-------

The service's backend is defined in chat-be. it exposes an akka cluster 
receptionist for the frontend to communicate with. It is not required to 
proceed as such, but it provides nice features. the root of the service 
consume user's actions and route those to the proper chat topic via 
clusterSharding. It is therefore recommended to run multiple instance 
of the backend.

Each Topic is responsible to managing user's actions such as creating a 
topic, adding a participant or posting a message.

Message are stored in individual persistent actor by the week of their 
creation. This avoid having to load and maintain in memory possibly years 
of message for every post. Currently those all reside on the same node as 
the topic, but we could use cluster sharding to distribute those. It could 
be useful in order to distribute action on all message, such as search (if
we don't use an index) or other kind of extraction/batch processing.

Frontend
--------

A frontend exposes rest apis through akka-http. It define diverses apis, 
for demonstration purpose, and create the internal protocol buffer message 
used for cluster communication and persistence.


possible enhancements : 

 - websocket support
 - gatling tests
 - multi node test
 - account service
 - message search
 - indexing (topic, participant)
 - use akka stream to provide chat data.
 - ...