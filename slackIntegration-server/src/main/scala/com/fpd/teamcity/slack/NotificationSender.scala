package com.fpd.teamcity.slack

import com.fpd.teamcity.slack.ConfigManager.BuildSetting
import com.fpd.teamcity.slack.ConfigManager.BuildSettingFlag.BuildSettingFlag
import com.fpd.teamcity.slack.SlackGateway.{Destination, MessageSent, SlackChannel, SlackUser}
import jetbrains.buildServer.serverSide.SBuild

import scala.collection.mutable

trait NotificationSender {

  val configManager: ConfigManager
  val gateway: SlackGateway
  val messageBuilderFactory: MessageBuilderFactory

  import Helpers.Implicits._

  type SendResult = Map[String, Option[MessageSent]]

  def send(build: SBuild, flags: Set[BuildSettingFlag]): SendResult = {
    val settings = prepareSettings(build, flags)

    lazy val emails = build.committees
    lazy val messageBuilder = messageBuilderFactory.createForBuild(build)
    lazy val sendPersonal = shouldSendPersonal(build)

    settings.foldLeft(Map(): SendResult) { (acc, setting) ⇒
      val attachment = messageBuilder.compile(setting.messageTemplate, Some(setting))
      val destinations = mutable.Set.empty[Destination]
      if (build.isPersonal) {
        // If build is personal we need inform only build's owner if needed
        val email = build.getOwner.getEmail
        if (sendPersonal && email.length > 0) {
          destinations += SlackUser(email)
        }
      } else {
        if (setting.slackChannel.nonEmpty) {
          destinations += SlackChannel(setting.slackChannel)
        }

        /**
          * if build fails all committees should receive the message
          * if personal notification explicitly enabled in build settings let's notify all committees
          */
        if (setting.notifyCommitter || sendPersonal) {
          emails.foreach { email ⇒
            destinations += SlackUser(email)
          }
        }
      }

      acc ++ destinations.map(x ⇒ x.toString → gateway.sendMessage(x, attachment)).toMap
    }
  }

  def shouldSendPersonal(build: SBuild): Boolean = build.getBuildStatus.isFailed && configManager.personalEnabled.exists(x ⇒ x)

  def prepareSettings(build: SBuild, flags: Set[BuildSettingFlag]): Iterable[BuildSetting] =
    configManager.buildSettingList(build.getBuildTypeId).values.filter { x ⇒
      x.pureFlags.intersect(flags).nonEmpty && build.matchBranch(x.branchMask)
    }
}
