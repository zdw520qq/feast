/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2018-2019 The Feast Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package feast.core.job.dataflow;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.initMocks;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.googleapis.testing.auth.oauth2.MockGoogleCredential;
import com.google.api.services.dataflow.Dataflow;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.protobuf.Duration;
import com.google.protobuf.util.JsonFormat;
import com.google.protobuf.util.JsonFormat.Printer;
import feast.core.config.FeastProperties.MetricsProperties;
import feast.core.exception.JobExecutionException;
import feast.core.job.Runner;
import feast.core.model.*;
import feast.ingestion.options.ImportOptions;
import feast.proto.core.FeatureSetProto;
import feast.proto.core.FeatureSetProto.FeatureSetMeta;
import feast.proto.core.FeatureSetProto.FeatureSetSpec;
import feast.proto.core.IngestionJobProto;
import feast.proto.core.RunnerProto.DataflowRunnerConfigOptions;
import feast.proto.core.RunnerProto.DataflowRunnerConfigOptions.Builder;
import feast.proto.core.SourceProto;
import feast.proto.core.SourceProto.KafkaSourceConfig;
import feast.proto.core.SourceProto.SourceType;
import feast.proto.core.StoreProto;
import feast.proto.core.StoreProto.Store.RedisConfig;
import feast.proto.core.StoreProto.Store.StoreType;
import feast.proto.core.StoreProto.Store.Subscription;
import java.io.IOException;
import org.apache.beam.runners.dataflow.DataflowPipelineJob;
import org.apache.beam.runners.dataflow.DataflowRunner;
import org.apache.beam.sdk.PipelineResult.State;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;

public class DataflowJobManagerTest {

  @Rule public final ExpectedException expectedException = ExpectedException.none();

  @Mock private Dataflow dataflow;

  private DataflowRunnerConfigOptions defaults;
  private IngestionJobProto.SpecsStreamingUpdateConfig specsStreamingUpdateConfig;
  private DataflowJobManager dfJobManager;

  @Before
  public void setUp() {
    initMocks(this);
    Builder optionsBuilder = DataflowRunnerConfigOptions.newBuilder();
    optionsBuilder.setProject("project");
    optionsBuilder.setRegion("region");
    optionsBuilder.setZone("zone");
    optionsBuilder.setTempLocation("tempLocation");
    optionsBuilder.setNetwork("network");
    optionsBuilder.setSubnetwork("subnetwork");
    optionsBuilder.putLabels("orchestrator", "feast");
    defaults = optionsBuilder.build();
    MetricsProperties metricsProperties = new MetricsProperties();
    metricsProperties.setEnabled(false);
    Credential credential = null;
    try {
      credential = MockGoogleCredential.getApplicationDefault();
    } catch (IOException e) {
      e.printStackTrace();
    }

    specsStreamingUpdateConfig =
        IngestionJobProto.SpecsStreamingUpdateConfig.newBuilder()
            .setSource(
                KafkaSourceConfig.newBuilder()
                    .setTopic("specs_topic")
                    .setBootstrapServers("servers:9092")
                    .build())
            .build();

    dfJobManager =
        new DataflowJobManager(defaults, metricsProperties, specsStreamingUpdateConfig, credential);
    dfJobManager = spy(dfJobManager);
  }

  @Test
  public void shouldStartJobWithCorrectPipelineOptions() throws IOException {
    StoreProto.Store store =
        StoreProto.Store.newBuilder()
            .setName("SERVING")
            .setType(StoreType.REDIS)
            .setRedisConfig(RedisConfig.newBuilder().setHost("localhost").setPort(6379).build())
            .addSubscriptions(Subscription.newBuilder().setProject("*").setName("*").build())
            .build();

    SourceProto.Source source =
        SourceProto.Source.newBuilder()
            .setType(SourceType.KAFKA)
            .setKafkaSourceConfig(
                KafkaSourceConfig.newBuilder()
                    .setTopic("topic")
                    .setBootstrapServers("servers:9092")
                    .build())
            .build();

    FeatureSetProto.FeatureSet featureSet =
        FeatureSetProto.FeatureSet.newBuilder()
            .setMeta(FeatureSetMeta.newBuilder())
            .setSpec(
                FeatureSetSpec.newBuilder()
                    .setSource(source)
                    .setName("featureSet")
                    .setMaxAge(Duration.newBuilder().build()))
            .build();

    Printer printer = JsonFormat.printer();
    String expectedExtJobId = "feast-job-0";
    String jobName = "job";

    ImportOptions expectedPipelineOptions =
        PipelineOptionsFactory.fromArgs("").as(ImportOptions.class);
    expectedPipelineOptions.setRunner(DataflowRunner.class);
    expectedPipelineOptions.setProject("project");
    expectedPipelineOptions.setRegion("region");
    expectedPipelineOptions.setUpdate(false);
    expectedPipelineOptions.setAppName("DataflowJobManager");
    expectedPipelineOptions.setLabels(defaults.getLabelsMap());
    expectedPipelineOptions.setJobName(jobName);
    expectedPipelineOptions.setStoresJson(Lists.newArrayList(printer.print(store)));
    expectedPipelineOptions.setSourceJson(printer.print(source));

    ArgumentCaptor<ImportOptions> captor = ArgumentCaptor.forClass(ImportOptions.class);

    DataflowPipelineJob mockPipelineResult = Mockito.mock(DataflowPipelineJob.class);
    when(mockPipelineResult.getState()).thenReturn(State.RUNNING);
    when(mockPipelineResult.getJobId()).thenReturn(expectedExtJobId);

    doReturn(mockPipelineResult).when(dfJobManager).runPipeline(any());

    FeatureSetJobStatus featureSetJobStatus = new FeatureSetJobStatus();
    featureSetJobStatus.setFeatureSet(FeatureSet.fromProto(featureSet));

    Job job =
        Job.builder()
            .setId(jobName)
            .setExtId("")
            .setRunner(Runner.DATAFLOW)
            .setSource(Source.fromProto(source))
            .setStores(ImmutableSet.of(Store.fromProto(store)))
            .setFeatureSetJobStatuses(Sets.newHashSet(featureSetJobStatus))
            .setStatus(JobStatus.PENDING)
            .build();
    Job actual = dfJobManager.startJob(job);

    verify(dfJobManager, times(1)).runPipeline(captor.capture());
    ImportOptions actualPipelineOptions = captor.getValue();

    expectedPipelineOptions.setOptionsId(
        actualPipelineOptions.getOptionsId()); // avoid comparing this value

    // We only check that we are calling getFilesToStage() manually, because the automatic approach
    // throws an error: https://github.com/feast-dev/feast/pull/291 i.e. do not check for the actual
    // files that are staged
    assertThat(
        "filesToStage in pipelineOptions should not be null, job manager should set it.",
        actualPipelineOptions.getFilesToStage() != null);
    assertThat(
        "filesToStage in pipelineOptions should contain at least 1 item",
        actualPipelineOptions.getFilesToStage().size() > 0);
    // Assume the files that are staged are correct
    expectedPipelineOptions.setFilesToStage(actualPipelineOptions.getFilesToStage());

    assertThat(
        actualPipelineOptions.getDeadLetterTableSpec(),
        equalTo(expectedPipelineOptions.getDeadLetterTableSpec()));
    assertThat(
        actualPipelineOptions.getStatsdHost(), equalTo(expectedPipelineOptions.getStatsdHost()));
    assertThat(
        actualPipelineOptions.getMetricsExporterType(),
        equalTo(expectedPipelineOptions.getMetricsExporterType()));
    assertThat(
        actualPipelineOptions.getStoresJson(), equalTo(expectedPipelineOptions.getStoresJson()));
    assertThat(
        actualPipelineOptions.getSourceJson(), equalTo(expectedPipelineOptions.getSourceJson()));
    assertThat(
        actualPipelineOptions.getSpecsStreamingUpdateConfigJson(),
        equalTo(printer.print(specsStreamingUpdateConfig)));
    assertThat(actual.getExtId(), equalTo(expectedExtJobId));
    assertThat(actual.getStatus(), equalTo(JobStatus.RUNNING));
  }

  @Test
  public void shouldThrowExceptionWhenJobStateTerminal() throws IOException {
    StoreProto.Store store =
        StoreProto.Store.newBuilder()
            .setName("SERVING")
            .setType(StoreType.REDIS)
            .setRedisConfig(RedisConfig.newBuilder().setHost("localhost").setPort(6379).build())
            .build();

    SourceProto.Source source =
        SourceProto.Source.newBuilder()
            .setType(SourceType.KAFKA)
            .setKafkaSourceConfig(
                KafkaSourceConfig.newBuilder()
                    .setTopic("topic")
                    .setBootstrapServers("servers:9092")
                    .build())
            .build();

    FeatureSetProto.FeatureSet featureSet =
        FeatureSetProto.FeatureSet.newBuilder()
            .setSpec(FeatureSetSpec.newBuilder().setName("featureSet").setSource(source).build())
            .build();

    dfJobManager = Mockito.spy(dfJobManager);

    DataflowPipelineJob mockPipelineResult = Mockito.mock(DataflowPipelineJob.class);
    when(mockPipelineResult.getState()).thenReturn(State.FAILED);

    doReturn(mockPipelineResult).when(dfJobManager).runPipeline(any());

    FeatureSetJobStatus featureSetJobStatus = new FeatureSetJobStatus();
    featureSetJobStatus.setFeatureSet(FeatureSet.fromProto(featureSet));

    Job job =
        Job.builder()
            .setId("job")
            .setExtId("")
            .setRunner(Runner.DATAFLOW)
            .setSource(Source.fromProto(source))
            .setStores(ImmutableSet.of(Store.fromProto(store)))
            .setFeatureSetJobStatuses(Sets.newHashSet(featureSetJobStatus))
            .setStatus(JobStatus.PENDING)
            .build();

    expectedException.expect(JobExecutionException.class);
    dfJobManager.startJob(job);
  }
}
