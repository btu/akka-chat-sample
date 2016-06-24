package btu.chat.backend

import btu.chat.ChatApi._

object Errors {
  def genericError(id: Short, msg: String) = ChatError.newBuilder.setErrorId(20000 + id).setErrorMessage(msg)

  private val _BadMessage = genericError(1, "Message is not defined in api")

  // Note the possible concurrency issue due to the reuse of the same builder.
  //
  // We should verify if protocol buffer returns a new builder on setRequestId, 
  // if not we should make sure we have an immutable object before calling build
  // for instance by not having _BadMessage a val but a def.
  //
  // I leave it that way since the sample is unlikely to run into the issue and 
  // that it would not be critical anyway
  def BadMessage(rqid: String) = _BadMessage.setRequestId(rqid).build
  def NotAMember(rqid: String) = genericError(3, "not a member").setRequestId(rqid).build
  def StreamNotFound(rqid: String) = genericError(4, "stream not found").setRequestId(rqid).build
  def StreamInProcess(rqid: String) = genericError(5, "A request is already being processed for this stream").setRequestId(rqid).build
}