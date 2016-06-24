
organization in ThisBuild := "btu"

version      in ThisBuild := "0.1-SNAPSHOT"

scalaVersion in ThisBuild := "2.11.8"

fork in run := true

val chatApi = project.in(file("chat-api"))

val chatBE = project.in(file("chat-be")).aggregate(chatApi).dependsOn(chatApi)

val frontend = project.in(file("rest-fe")).aggregate(chatApi).dependsOn(chatApi)