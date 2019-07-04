package packer

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path }

import ansible.PlaybookGenerator
import event.EventBus
import models.Bake
import models.packer.PackerVariablesConfig
import play.api.libs.json.Json
import schedule.BakeQueue
import services.{ Loggable, PrismAgents }

import scala.concurrent.{ Future, Promise }
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Try

case class PackerJob(bake: Bake, playbookFile: Path, packerConfigFile: Path, eventBus: EventBus, debug: Boolean) {
  private val packerCmd = sys.props.get("packerHome").map(ph => s"$ph/packer").getOrElse("packer")

  def execute(): Future[Int] = {
    val maybeDebug = if (debug) Some("-debug") else None
    val command = Seq(packerCmd, "build", maybeDebug, "-machine-readable", packerConfigFile.toAbsolutePath.toString) collect {
      case s: String => s
      case Some(s: String) => s
    }
    val packerProcess = new ProcessBuilder()
      .command(command: _*)
      .directory(new File(System.getProperty("java.io.tmpdir")))
      .start()

    val exitValuePromise = Promise[Int]()

    val runnable = new Runnable {
      def run(): Unit = PackerProcessMonitor.monitorProcess(packerProcess, exitValuePromise, bake.bakeId, eventBus)
    }
    val listenerThread = new Thread(runnable, s"Packer process monitor for ${bake.recipe.id.value} #${bake.buildNumber}")
    listenerThread.setDaemon(true)
    listenerThread.start()

    val exitValueFuture = exitValuePromise.future

    // Make sure to delete the tmp files after Packer completes, regardless of success or failure
    exitValueFuture.onComplete {
      case _ =>
        Try(Files.deleteIfExists(playbookFile))
        Try(Files.deleteIfExists(packerConfigFile))
    }

    exitValueFuture
  }
}

object PackerRunner extends Loggable {

  /**
   * Starts a Packer process to create an image using the given recipe.
   *
   * @return a Future of the process's exit value
   */
  def createImage(bake: Bake, prism: PrismAgents, eventBus: EventBus, ansibleVars: Map[String, String], debug: Boolean, queue: Boolean = false)(implicit packerConfig: PackerConfig): Unit = {
    val playbookYaml = PlaybookGenerator.generatePlaybook(bake.recipe, ansibleVars)
    val playbookFile = Files.createTempFile(s"amigo-ansible-${bake.recipe.id.value}", ".yml")
    Files.write(playbookFile, playbookYaml.getBytes(StandardCharsets.UTF_8)) // TODO error handling

    val awsAccountNumbers = prism.accounts.map(_.accountNumber)
    log.info(s"AMI will be shared with the following AWS accounts: $awsAccountNumbers")
    val packerVars = PackerVariablesConfig(bake)
    val packerBuildConfig = PackerBuildConfigGenerator.generatePackerBuildConfig(bake, playbookFile, packerVars, awsAccountNumbers)
    val packerJson = Json.prettyPrint(Json.toJson(packerBuildConfig))
    val packerConfigFile = Files.createTempFile(s"amigo-packer-${bake.recipe.id.value}", ".json")
    Files.write(packerConfigFile, packerJson.getBytes(StandardCharsets.UTF_8)) // TODO error handling
    val job = PackerJob(bake, playbookFile, packerConfigFile, eventBus, debug)

    if (queue) BakeQueue.bakeQueue.add(job) else job.execute()
  }

}