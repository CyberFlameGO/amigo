package schedule

import com.amazonaws.services.ec2.model.{ DescribeInstancesRequest, Filter }
import data.{ BakeLogs, Bakes, Dynamo }
import models.BakeId
import notification.NotificationSender
import org.quartz.SimpleScheduleBuilder
import prism.RecipeUsage
import services.{ Loggable, PrismAgents }

/*
  This class deletes bakes that have been marked deleted
 */
class BakeDeletion(dynamo: Dynamo,
    amigoAwsAccount: String,
    prismAgents: PrismAgents,
    notificationSender: NotificationSender) extends ScheduledJob with Loggable {

  implicit private val implDynamo: Dynamo = dynamo
  implicit private val implPrismAgents: PrismAgents = prismAgents

  override val schedule = SimpleScheduleBuilder.repeatMinutelyForever(1)

  def scheduleAction(): Unit = {
    log.info(s"Started bake deletion housekeeping")

    // get some bakes that have been deleted
    val deletedBakes = Bakes.findDeleted()

    if (deletedBakes.nonEmpty) log.info(s"Found ${deletedBakes.size} bakes to delete")

    // delete any AMIs
    val amis = deletedBakes.flatMap(_.amiId)
    val allAmis = RecipeUsage.allAmis(amis, amigoAwsAccount)
    notificationSender.sendHousekeepingTopicMessage(allAmis)

    new DescribeInstancesRequest().withFilters(new Filter())

    // delete the logs and bakes
    deletedBakes.foreach { bake =>
      val bakeId = BakeId(bake.recipeId, bake.buildNumber)
      log.info(s"Deleting $bakeId")
      BakeLogs.delete(bakeId)
      Bakes.deleteById(bakeId)
      // avoid overwhelming the DB by pausing briefly before the next one
      Thread.sleep(2000)
    }
  }
}
