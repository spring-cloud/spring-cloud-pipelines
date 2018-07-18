package org.springframework.cloud.pipelines.spinnaker.pipeline

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import groovy.transform.CompileStatic

import org.springframework.cloud.pipelines.common.PipelineDefaults
import org.springframework.cloud.pipelines.common.PipelineDescriptor
import org.springframework.cloud.pipelines.spinnaker.SpinnakerDefaults
import org.springframework.cloud.pipelines.spinnaker.pipeline.model.Artifact
import org.springframework.cloud.pipelines.spinnaker.pipeline.model.Capacity
import org.springframework.cloud.pipelines.spinnaker.pipeline.model.Cluster
import org.springframework.cloud.pipelines.spinnaker.pipeline.model.Manifest
import org.springframework.cloud.pipelines.spinnaker.pipeline.model.Root
import org.springframework.cloud.pipelines.spinnaker.pipeline.model.Stage
import org.springframework.cloud.pipelines.spinnaker.pipeline.model.Trigger
import org.springframework.cloud.repositorymanagement.Repository

/**
 * Given the env variables produces a JSON with a Spinnaker
 * opinionated deployment pipeline
 *
 * @author Marcin Grzejszczak
 * @since 1.0.0
 */
@CompileStatic
class SpinnakerPipelineBuilder {

	private final PipelineDescriptor pipelineDescriptor
	private final Repository repository
	private final PipelineDefaults defaults
	private final ObjectMapper objectMapper = new ObjectMapper()

	SpinnakerPipelineBuilder(PipelineDescriptor pipelineDescriptor, Repository repository,
							 PipelineDefaults defaults) {
		this.pipelineDescriptor = pipelineDescriptor
		this.repository = repository
		this.defaults = defaults
		this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT)
		this.objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
		this.objectMapper.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
	}

	String spinnakerPipeline() {
		return objectMapper.writerFor(Root).writeValueAsString(root())
	}

	private Root root() {
		Root root = new Root()
		root.triggers.add(trigger())
		root.stages = stages()
		return root
	}

	private List<Stage> stages() {
		List<Stage> stages = []
		// Test Create Service 1 (x)
		// Test Create Service 2 (x)
		int firstRefId = 1
		Tuple2<Integer, List<Stage>> testServices =
			createTestServices("test", firstRefId, pipelineDescriptor.test.services)
		stages.addAll(testServices.second)
		// Deploy to test
		Tuple2<Integer, Stage> testDeployment = testDeploymentStage(testServices.first)
		stages.add(testDeployment.second)
		// Test on test
		Tuple2<Integer, Stage> testsOnTest = runTests("test", "Run tests on test",
			"test", testDeployment.first)
		stages.add(testsOnTest.second)
		// Deploy to test latest prod version
		Tuple2<Integer, Stage> testDeploymentRollback =
			rollbackDeploymentStage("Deploy to test latest prod version",
				testsOnTest.first,
				valueOrDefaultIfNull(pipelineDescriptor.pipeline.rollback_step, true))
		stages.add(testDeploymentRollback.second)
		// Test on test latest prod version
		Tuple2<Integer, Stage> rollbackTests = runTests("test", "Run rollback tests on test",
			"rollback-test", testDeploymentRollback.first,
			valueOrDefaultIfNull(pipelineDescriptor.pipeline.rollback_step, true))
		stages.add(rollbackTests.second)
		// Wait for stage env
		Tuple2<Integer, Stage> waitingForStage = manualJudgement(
			!valueOrDefaultIfNull(pipelineDescriptor.pipeline.stage_step, true) ||
			valueOrDefaultIfNull(pipelineDescriptor.pipeline.auto_stage, false),
			"Wait for stage env", rollbackTests.first)
		stages.add(waitingForStage.second)
		// Stage Create Service 1
		// Stage Create Service 2
		Tuple2<Integer, List<Stage>> stageServices = createStageServices("stage",
			waitingForStage.first, pipelineDescriptor.stage.services)
		stages.addAll(stageServices.second)
		// Deploy to stage
		Tuple2<Integer, Stage> stageDeployment =
			deploymentStage("Deploy to stage", waitingForStage.first,
				stageServices.first,
				valueOrDefaultIfNull(pipelineDescriptor.pipeline.stage_step, true))
		stages.add(stageDeployment.second)
		// Prepare for end to end tests
		Tuple2<Integer, Stage> prepareForE2e = manualJudgement(
			!valueOrDefaultIfNull(pipelineDescriptor.pipeline.stage_step, true) ||
			valueOrDefaultIfNull(pipelineDescriptor.pipeline.auto_stage, false),
			"Prepare for end to end tests", stageDeployment.first)
		stages.add(prepareForE2e.second)
		// Run end to end tests
		Tuple2<Integer, Stage> e2eTests = runTests("stage", "End to end tests on stage",
			"e2e", prepareForE2e.first,
			valueOrDefaultIfNull(pipelineDescriptor.pipeline.stage_step, true))
		stages.add(e2eTests.second)
		// Approve production
		Tuple2<Integer, Stage> approveProd = manualJudgement(
			valueOrDefaultIfNull(pipelineDescriptor.pipeline.auto_prod, false),
			"Approve production",
			e2eTests.first)
		stages.add(approveProd.second)
		// Deploy to prod
		Tuple2<Integer, Stage> deployToProd =
			prodDeployment("Deploy to prod", approveProd.first, approveProd.first)
		stages.add(deployToProd.second)
		// Rollback
		Tuple2<Integer, Stage> rollback =
			prodDeployment("Rollback", approveProd.first, deployToProd.first)
		stages.add(rollback.second)
		return stages.findAll { it }
	}

	private boolean valueOrDefaultIfNull(Boolean value, boolean defaultValue) {
		return value == null ? defaultValue : value
	}

	private Tuple2<Integer, List<Stage>> createTestServices(String env, int firstId,
															List<PipelineDescriptor.Service> pipeServices) {
		if (!pipeServices) {
			return new Tuple2(firstId, [])
		}
		List<Stage> testServices = []
		List<PipelineDescriptor.Service> services = pipeServices
		int refId = 1
		for (int i = 0; i < services.size(); i++) {
			refId = i + firstId
			testServices.add(new Stage(
				name: "Create ${env} service [${i}]",
				refId: "${refId}",
				type: "wait",
				waitTime: 1
			))
		}
		return new Tuple2(refId, testServices)
	}

	private Tuple2<Integer, List<Stage>> createStageServices(String env, int firstId,
															 List<PipelineDescriptor.Service> pipeServices) {
		if (!pipeServices ||
			!valueOrDefaultIfNull(pipelineDescriptor.pipeline.stage_step, true)) {
			return new Tuple2(firstId, [])
		}
		List<Stage> testServices = []
		List<PipelineDescriptor.Service> services = pipeServices
		int refId = 1
		for (int i = 0; i < services.size(); i++) {
			refId = i + 1 + firstId
			testServices.add(new Stage(
				name: "Create ${env} service [${i}]",
				refId: "${refId}",
				requisiteStageRefIds: [
					"${firstId}".toString()
				],
				type: "wait",
				waitTime: 1
			))
		}
		return new Tuple2(refId, testServices)
	}

	private Cluster cluster(String account, String org, String space,
							String deploymentStrategy) {
		return new Cluster(
			account: account,
			application: "${this.repository.name}",
			artifact: new Artifact(
				account: "jenkins",
				reference: "${this.repository.name}.*.jar",
				type: "artifact"
			),
			capacity: new Capacity(
				desired: "1",
				max: "1",
				min: "1"
			),
			cloudProvider: "cloudfoundry",
			detail: "",
			manifest: new Manifest(
				diskQuota: "1024M",
				env: [],
				instances: 1,
				memory: "1024M",
				services: [],
				type: "direct"
			),
			provider: "cloudfoundry",
			region: "${org}/${space}",
			stack: "",
			strategy: deploymentStrategy
		)
	}

	private Tuple2<Integer, Stage> testDeploymentStage(int lastRefId) {
		int refId = pipelineDescriptor.test.services.empty ?
			1 : lastRefId + 1
		Stage stage = new Stage(
			name: "Deploy to test",
			refId: "${refId}",
			requisiteStageRefIds: pipelineDescriptor.test.services.empty ?
				[] as List<String> : intToRange(1, lastRefId),
			type: "deploy",
			clusters: [
				cluster(defaults.spinnakerTestDeploymentAccount(),
					defaults.cfTestOrg(), testSpaceName(),
				"highlander")
			]
		)
		return new Tuple2(refId, stage)
	}

	private String testSpaceName() {
		return defaults.cfTestSpacePrefix() + "-" + repository.name
	}

	private Tuple2<Integer, Stage> deploymentStage(String text, int firstRefId, int lastRefId,
												   boolean present) {
		if (!present) {
			return new Tuple2<>(firstRefId, null)
		}
		int refId = lastRefId + 1
		int startRange = firstRefId != lastRefId ? firstRefId + 1 : firstRefId
		Stage stage = new Stage(
			name: text,
			refId: "${refId}",
			requisiteStageRefIds: intToRange(startRange, lastRefId),
			type: "deploy",
			clusters: [
				cluster(defaults.spinnakerStageDeploymentAccount(),
					defaults.cfStageOrg(), defaults.cfStageSpace(),
				"highlander")
			]
		)
		return new Tuple2(refId, stage)
	}

	private Tuple2<Integer, Stage> prodDeployment(String text, int idToReference, int lastRefId) {
		int refId = lastRefId + 1
		Stage stage = new Stage(
			name: text,
			refId: "${refId}",
			requisiteStageRefIds: [
				"${idToReference}".toString()
			],
			type: "deploy",
			clusters: [
				cluster(defaults.spinnakerProdDeploymentAccount(),
					defaults.cfProdOrg(), defaults.cfProdSpace(),
				pipelineDescriptor.prod.deployment_strategy ?: "highlander")
			]
		)
		return new Tuple2(refId, stage)
	}

	private Tuple2<Integer, Stage> rollbackDeploymentStage(String text, int firstRefId, boolean present) {
		if (!present) {
			return new Tuple2(firstRefId, null)
		}
		int refId = firstRefId + 1
		Stage stage = new Stage(
			name: text,
			refId: "${refId}",
			requisiteStageRefIds: [
				"${firstRefId}".toString()
			],
			type: "deploy",
			clusters: [
				cluster(defaults.spinnakerProdDeploymentAccount(),
					defaults.cfTestOrg(), testSpaceName(),
					pipelineDescriptor.prod.deployment_strategy ?: "highlander")
			]
		)
		return new Tuple2(refId, stage)
	}

	private Tuple2<Integer, Stage> manualJudgement(boolean skip,
												   String text, int firstRefId) {
		if (skip) {
			return new Tuple2(firstRefId, null)
		}
		int refId = firstRefId + 1
		Stage stage = new Stage(
			failPipeline: true,
			judgmentInputs: [],
			name: text,
			notifications: [],
			refId: "${refId}",
			requisiteStageRefIds: [
				"${firstRefId}".toString()
			],
			type: "manualJudgment"
		)
		return new Tuple2(refId, stage)
	}

	private Tuple2<Integer, Stage> runTests(String env, String text, String testName,
											int firstRefId, boolean present = true) {
		if (!present) {
			return new Tuple2(firstRefId, null)
		}
		int refId = firstRefId + 1
		Stage stage = new Stage(
			continuePipeline: false,
			failPipeline: true,
			job: "${SpinnakerDefaults.projectName(repository.name)}-${env}-env-${testName}",
			master: defaults.spinnakerJenkinsMaster(),
			name: "${text}",
			parameters: [:],
			refId: "${refId}",
			requisiteStageRefIds: [
				"${firstRefId}".toString()
			],
			waitForCompletion: true,
			type: "jenkins"
		)
		return new Tuple2(refId, stage)
	}

	private List<String> intToRange(int firstRefId, int lastRefId) {
		return (firstRefId..lastRefId).collect {
			"${it}".toString()
		}
	}

	private Trigger trigger() {
		return new Trigger(
			enabled: true,
			job: "spinnaker-${repository.name}-pipeline-build",
			master: defaults.spinnakerJenkinsMaster(),
			type: "jenkins"
		)
	}
}
