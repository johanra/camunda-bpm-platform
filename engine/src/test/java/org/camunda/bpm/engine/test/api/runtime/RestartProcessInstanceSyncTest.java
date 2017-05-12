package org.camunda.bpm.engine.test.api.runtime;

import static org.camunda.bpm.engine.test.api.runtime.migration.ModifiableBpmnModelInstance.modify;
import static org.camunda.bpm.engine.test.util.ActivityInstanceAssert.assertThat;
import static org.camunda.bpm.engine.test.util.ActivityInstanceAssert.describeActivityInstanceTree;
import static org.junit.Assert.*;

import java.util.List;

import static org.hamcrest.CoreMatchers.containsString;
import org.camunda.bpm.engine.BadUserRequestException;
import org.camunda.bpm.engine.ProcessEngineConfiguration;
import org.camunda.bpm.engine.ProcessEngineException;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.TaskService;
import org.camunda.bpm.engine.batch.Batch;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.ExecutionListener;
import org.camunda.bpm.engine.history.HistoricProcessInstanceQuery;
import org.camunda.bpm.engine.repository.ProcessDefinition;
import org.camunda.bpm.engine.runtime.ActivityInstance;
import org.camunda.bpm.engine.runtime.Execution;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.camunda.bpm.engine.runtime.VariableInstance;
import org.camunda.bpm.engine.task.Task;
import org.camunda.bpm.engine.test.ProcessEngineRule;
import org.camunda.bpm.engine.test.RequiredHistoryLevel;
import org.camunda.bpm.engine.test.api.runtime.migration.models.ProcessModels;
import org.camunda.bpm.engine.test.api.runtime.util.IncrementCounterListener;
import org.camunda.bpm.engine.test.util.ProcessEngineTestRule;
import org.camunda.bpm.engine.test.util.ProvidedProcessEngineRule;
import org.camunda.bpm.model.bpmn.Bpmn;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;


/**
 * 
 * @author Anna Pazola
 *
 */
@RequiredHistoryLevel(ProcessEngineConfiguration.HISTORY_FULL)
public class RestartProcessInstanceSyncTest {

  protected ProcessEngineRule engineRule = new ProvidedProcessEngineRule();
  protected ProcessEngineTestRule testRule = new ProcessEngineTestRule(engineRule);

  protected RuntimeService runtimeService;
  protected TaskService taskService;

  @Before
  public void init() {
    runtimeService = engineRule.getRuntimeService();
    taskService = engineRule.getTaskService();
  }

  @Rule
  public RuleChain ruleChain = RuleChain.outerRule(engineRule).around(testRule);

  @Test
  public void shouldRestartSimpleProcessInstance() {
    // given
    ProcessDefinition processDefinition = testRule.deployAndGetDefinition(ProcessModels.ONE_TASK_PROCESS);
    ProcessInstance processInstance = runtimeService.startProcessInstanceByKey("Process");

    Task task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).active().singleResult();
    // process instance was deleted
    runtimeService.deleteProcessInstance(processInstance.getId(), "test");

    // when
    runtimeService.restartProcessInstances(processDefinition.getId())
    .startBeforeActivity("userTask")
    .processInstanceIds(processInstance.getId())
    .execute();

    // then
    ProcessInstance restartedProcessInstance = runtimeService.createProcessInstanceQuery().active().singleResult();
    Task restartedTask = engineRule.getTaskService().createTaskQuery().processInstanceId(restartedProcessInstance.getId()).active().singleResult();
    Assert.assertEquals(task.getTaskDefinitionKey(), restartedTask.getTaskDefinitionKey());
  }

  @Test
  public void shouldRestartProcessInstanceWithTwoTasks() {
    // given
    ProcessDefinition processDefinition = testRule.deployAndGetDefinition(ProcessModels.TWO_TASKS_PROCESS);
    ProcessInstance processInstance = runtimeService.startProcessInstanceByKey("Process");

    // the first task is completed
    Task userTask1 = taskService.createTaskQuery().processInstanceId(processInstance.getId()).active().singleResult();
    taskService.complete(userTask1.getId());
    Task userTask2 = taskService.createTaskQuery().processInstanceId(processInstance.getId()).active().singleResult();
    // delete process instance
    runtimeService.deleteProcessInstance(processInstance.getId(), "test");

    // when
    runtimeService.restartProcessInstances(processDefinition.getId())
    .startBeforeActivity("userTask2")
    .processInstanceIds(processInstance.getId())
    .execute();

    // then
    ProcessInstance restartedProcessInstance = runtimeService.createProcessInstanceQuery().active().singleResult();
    Task restartedTask = taskService.createTaskQuery().processInstanceId(restartedProcessInstance.getId()).active().singleResult();
    Assert.assertEquals(userTask2.getTaskDefinitionKey(), restartedTask.getTaskDefinitionKey());
    
    ActivityInstance updatedTree = runtimeService.getActivityInstance(restartedProcessInstance.getId());
    assertNotNull(updatedTree);
    assertEquals(restartedProcessInstance.getId(), updatedTree.getProcessInstanceId());
    assertThat(updatedTree).hasStructure(
        describeActivityInstanceTree(
            processDefinition.getId())
        .activity("userTask2")
        .done());

  }

  @Test
  public void shouldRestartProcessInstanceWithParallelGateway() {
    // given
    ProcessDefinition processDefinition = testRule.deployAndGetDefinition(ProcessModels.PARALLEL_GATEWAY_PROCESS);
    ProcessInstance processInstance = runtimeService.startProcessInstanceByKey("Process");
    runtimeService.deleteProcessInstance(processInstance.getId(), "test");

    // when
    runtimeService.restartProcessInstances(processDefinition.getId())
    .startBeforeActivity("userTask1")
    .startBeforeActivity("userTask2")
    .processInstanceIds(processInstance.getId())
    .execute();

    // then
    ProcessInstance restartedProcessInstance = runtimeService.createProcessInstanceQuery().active().singleResult();
    ActivityInstance updatedTree = runtimeService.getActivityInstance(restartedProcessInstance.getId());
    assertNotNull(updatedTree);
    assertEquals(restartedProcessInstance.getId(), updatedTree.getProcessInstanceId());
    assertThat(updatedTree).hasStructure(
        describeActivityInstanceTree(
            processDefinition.getId())
        .activity("userTask1")
        .activity("userTask2")
        .done());
  }

  @Test
  public void shouldRestartProcessInstanceWithSubProcess() {
    // given
    ProcessDefinition processDefinition = testRule.deployAndGetDefinition(ProcessModels.SUBPROCESS_PROCESS);
    ProcessInstance processInstance = runtimeService.startProcessInstanceByKey("Process");
    runtimeService.deleteProcessInstance(processInstance.getId(), "test");

    // when
    runtimeService.restartProcessInstances(processDefinition.getId())
    .startBeforeActivity("subProcess")
    .processInstanceIds(processInstance.getId())
    .execute();

    // then
    ProcessInstance restartedProcessInstance = runtimeService.createProcessInstanceQuery().active().singleResult();
    ActivityInstance updatedTree = runtimeService.getActivityInstance(restartedProcessInstance.getId());
    assertNotNull(updatedTree);
    assertEquals(restartedProcessInstance.getId(), updatedTree.getProcessInstanceId());
    assertThat(updatedTree).hasStructure(
        describeActivityInstanceTree(
            processDefinition.getId())
        .beginScope("subProcess")
        .activity("userTask")
        .done());
  }
  
  @Test
  public void shouldRestartProcessInstanceWithVariables() {
    // given
    BpmnModelInstance instance = Bpmn.createExecutableProcess("Process")
        .startEvent()
        .userTask("userTask1")
        .camundaExecutionListenerClass(ExecutionListener.EVENTNAME_END, SetVariableExecutionListenerImpl.class.getName())
        .userTask("userTask2")
        .endEvent()
        .done();

    ProcessDefinition processDefinition = testRule.deployAndGetDefinition(instance);
    ProcessInstance processInstance = runtimeService.startProcessInstanceByKey("Process");
    // variable is set at the beginning
    runtimeService.setVariable(processInstance.getId(), "var", "bar");

    // variable is changed
    Task task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).active().singleResult();
    taskService.complete(task.getId());

    runtimeService.deleteProcessInstance(processInstance.getId(), "test");

    // when
    runtimeService.restartProcessInstances(processDefinition.getId())
    .startBeforeActivity("userTask1")
    .processInstanceIds(processInstance.getId())
    .execute();

    // then
    ProcessInstance restartedProcessInstance = runtimeService.createProcessInstanceQuery().active().singleResult();
    VariableInstance variableInstance = runtimeService.createVariableInstanceQuery().processInstanceIdIn(restartedProcessInstance.getId()).singleResult();

    assertEquals(variableInstance.getExecutionId(), restartedProcessInstance.getId());
    assertEquals("var", variableInstance.getName());
    assertEquals("foo", variableInstance.getValue());
  }
  
  @Test
  public void shouldRestartProcessInstanceWithInitialVariables() {
    // given
    BpmnModelInstance instance = Bpmn.createExecutableProcess("Process")
        .startEvent()
        .userTask("userTask1")
        .camundaExecutionListenerClass(ExecutionListener.EVENTNAME_END, SetVariableExecutionListenerImpl.class.getName())
        .userTask("userTask2")
        .endEvent()
        .done();

    ProcessDefinition processDefinition = testRule.deployAndGetDefinition(instance);
    ProcessInstance processInstance = runtimeService.startProcessInstanceByKey("Process");
    // initial variable
    runtimeService.setVariable(processInstance.getId(), "var", "bar");

    // variable update
    Task task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).active().singleResult();
    taskService.complete(task.getId());

    // delete process instance
    runtimeService.deleteProcessInstance(processInstance.getId(), "test");

    // when
    runtimeService.restartProcessInstances(processDefinition.getId())
    .startBeforeActivity("userTask1")
    .initialSetOfVariables()
    .processInstanceIds(processInstance.getId())
    .execute();

    // then
    ProcessInstance restartedProcessInstance = runtimeService.createProcessInstanceQuery().active().singleResult();
    VariableInstance variableInstance = runtimeService.createVariableInstanceQuery().processInstanceIdIn(restartedProcessInstance.getId()).singleResult();

    assertEquals(variableInstance.getExecutionId(), restartedProcessInstance.getId());
    assertEquals("var", variableInstance.getName());
    assertEquals("bar", variableInstance.getValue());
  }

  @Test
  public void shouldNotSetLocalVariables() {
    // given
    ProcessDefinition processDefinition = testRule.deployAndGetDefinition(ProcessModels.SUBPROCESS_PROCESS);
    ProcessInstance processInstance = runtimeService.startProcessInstanceByKey("Process");

    Execution subProcess = runtimeService.createExecutionQuery().processInstanceId(processInstance.getId()).activityId("userTask").singleResult();
    runtimeService.setVariableLocal(subProcess.getId(), "local", "foo");
    runtimeService.setVariable(processInstance.getId(), "var", "bar");

    runtimeService.deleteProcessInstance(processInstance.getId(), "test");

    // when
    runtimeService.restartProcessInstances(processDefinition.getId())
    .startBeforeActivity("userTask")
    .processInstanceIds(processInstance.getId())
    .execute();

    // then
    ProcessInstance restartedProcessInstance = runtimeService.createProcessInstanceQuery().processDefinitionId(processDefinition.getId()).active().singleResult();
    List<VariableInstance> variables = runtimeService.createVariableInstanceQuery().processInstanceIdIn(restartedProcessInstance.getId()).list();
    assertEquals(1, variables.size());
    assertEquals("var", variables.get(0).getName());
    assertEquals("bar", variables.get(0).getValue());
  }

  @Test
  public void shouldRestartProcessInstanceUsingHistoricProcessInstanceQuery() {
    // given
    ProcessDefinition processDefinition = testRule.deployAndGetDefinition(ProcessModels.TWO_TASKS_PROCESS);
    ProcessInstance processInstance = runtimeService.startProcessInstanceByKey("Process");
    runtimeService.deleteProcessInstance(processInstance.getId(), "test");

    // when
    HistoricProcessInstanceQuery historicProcessInstanceQuery = engineRule.getHistoryService()
        .createHistoricProcessInstanceQuery()
        .processDefinitionId(processDefinition.getId());

    runtimeService.restartProcessInstances(processDefinition.getId())
    .startBeforeActivity("userTask1")
    .historicProcessInstanceQuery(historicProcessInstanceQuery)
    .execute();

    // then
    ProcessInstance restartedProcessInstance = runtimeService.createProcessInstanceQuery().active().singleResult();

    ActivityInstance updatedTree = runtimeService.getActivityInstance(restartedProcessInstance.getId());
    assertNotNull(updatedTree);
    assertEquals(restartedProcessInstance.getId(), updatedTree.getProcessInstanceId());
    assertThat(updatedTree).hasStructure(
        describeActivityInstanceTree(
            processDefinition.getId())
        .activity("userTask1")
        .done());
  }
  
  @Test
  public void restartProcessInstanceWithNullProcessDefinitionId() {
    try {
      runtimeService.restartProcessInstances(null).execute();
      fail("exception expected");
    } catch (BadUserRequestException e) {
      Assert.assertThat(e.getMessage(), containsString("processDefinitionId is null"));
    }
  }
  
  @Test
  public void restartProcessInstanceWithoutProcessInstanceIds() {
    try {
      runtimeService.restartProcessInstances("foo").startAfterActivity("bar").execute();
      fail("exception expected");
    } catch (BadUserRequestException e) {
      Assert.assertThat(e.getMessage(), containsString("processInstanceIds is empty"));
    }
  }

  @Test
  public void restartProcessInstanceWithoutInstructions() {
    try {
      runtimeService.restartProcessInstances("foo").processInstanceIds("bar").execute();
      fail("exception expected");
    } catch (BadUserRequestException e) {
      Assert.assertThat(e.getMessage(), containsString("instructions is empty"));
    }
  }

  @Test
  public void restartProcessInstanceWithNullProcessInstanceId() {
    ProcessDefinition processDefinition = testRule.deployAndGetDefinition(ProcessModels.ONE_TASK_PROCESS);
    try {
      runtimeService.restartProcessInstances(processDefinition.getId()).startAfterActivity("bar").processInstanceIds((String) null).execute();
      fail("exception expected");
    } catch (BadUserRequestException e) {
      Assert.assertThat(e.getMessage(), containsString("historicProcessInstanceId is null"));
    }
  }
  
  @Test
  public void restartNotExistingProcessInstance() {
    ProcessDefinition processDefinition = testRule.deployAndGetDefinition(ProcessModels.ONE_TASK_PROCESS);
    try {
      runtimeService.restartProcessInstances(processDefinition.getId()).startBeforeActivity("bar").processInstanceIds("aaa").execute();
      fail("exception expected");
    } catch (BadUserRequestException e) {
      Assert.assertThat(e.getMessage(), containsString("the historic process instance cannot be found"));
    }
  }
  
  @Test
  public void restartProcessInstanceWithNotMatchingProcessDefinition() {
    BpmnModelInstance instance = Bpmn.createExecutableProcess("Process2").startEvent().userTask().endEvent().done();
    ProcessDefinition processDefinition = testRule.deployAndGetDefinition(instance);
    ProcessDefinition processDefinition2 = testRule.deployAndGetDefinition(ProcessModels.TWO_TASKS_PROCESS);
    ProcessInstance processInstance = runtimeService.startProcessInstanceByKey("Process");
    try {
      runtimeService.restartProcessInstances(processDefinition.getId()).startBeforeActivity("userTask").processInstanceIds(processInstance.getId()).execute();
      fail("exception expected");
    } catch (ProcessEngineException e) {
      Assert.assertThat(e.getMessage(), containsString("Its process definition '" + processDefinition2.getId() + "' does not match given process definition '" + processDefinition.getId() +"'" ));
    }
  }

  @Test
  public void shouldRestartProcessInstanceWithBusinessKey() {
    // given
    ProcessDefinition processDefinition = testRule.deployAndGetDefinition(ProcessModels.TWO_TASKS_PROCESS);
    ProcessInstance processInstance = runtimeService.startProcessInstanceByKey("Process", "businessKey", (String) null);
    runtimeService.deleteProcessInstance(processInstance.getId(), "test");

    // when
    runtimeService.restartProcessInstances(processDefinition.getId())
    .startBeforeActivity("userTask1")
    .processInstanceIds(processInstance.getId())
    .execute();

    // then
    ProcessInstance restartedProcessInstance = runtimeService.createProcessInstanceQuery().processDefinitionId(processDefinition.getId()).active().singleResult();
    assertEquals(restartedProcessInstance.getBusinessKey(), processInstance.getBusinessKey());
  }

  @Test
  public void shouldRestartProcessInstanceWithCaseInstanceId() {
    // given
    ProcessDefinition processDefinition = testRule.deployAndGetDefinition(ProcessModels.TWO_TASKS_PROCESS);
    ProcessInstance processInstance = runtimeService.startProcessInstanceByKey("Process", null, "caseInstanceId");
    runtimeService.deleteProcessInstance(processInstance.getId(), "test");

    // when
    runtimeService.restartProcessInstances(processDefinition.getId())
    .startBeforeActivity("userTask1")
    .processInstanceIds(processInstance.getId())
    .execute();

    // then
    ProcessInstance restartedProcessInstance = runtimeService.createProcessInstanceQuery().processDefinitionId(processDefinition.getId()).active().singleResult();
    assertEquals(restartedProcessInstance.getCaseInstanceId(), processInstance.getCaseInstanceId());
  }

  @Test
  public void shouldRestartProcessInstanceWithTenant() {
    // given
    ProcessDefinition processDefinition = testRule.deployForTenantAndGetDefinition("tenantId", ProcessModels.TWO_TASKS_PROCESS);
    ProcessInstance processInstance = runtimeService.startProcessInstanceByKey("Process");

    runtimeService.deleteProcessInstance(processInstance.getId(), "test");
    // when
    runtimeService.restartProcessInstances(processDefinition.getId())
    .startBeforeActivity("userTask1")
    .processInstanceIds(processInstance.getId())
    .execute();

    // then
    ProcessInstance restartedProcessInstance = runtimeService.createProcessInstanceQuery().processDefinitionId(processDefinition.getId()).active().singleResult();
    assertNotNull(restartedProcessInstance.getTenantId());
    assertEquals(processInstance.getTenantId(), restartedProcessInstance.getTenantId());
  }

  @Test
  public void shouldSkipCustomListeners() {
    // given
    ProcessDefinition processDefinition = testRule.deployAndGetDefinition(modify(ProcessModels.TWO_TASKS_PROCESS).activityBuilder("userTask1")
        .camundaExecutionListenerClass(ExecutionListener.EVENTNAME_START, IncrementCounterListener.class.getName()).done());
    ProcessInstance processInstance = runtimeService.startProcessInstanceByKey("Process");

    runtimeService.deleteProcessInstance(processInstance.getId(), "test");

    IncrementCounterListener.counter = 0;
    // when
    runtimeService.restartProcessInstances(processDefinition.getId())
    .startBeforeActivity("userTask1")
    .processInstanceIds(processInstance.getId())
    .skipCustomListeners()
    .execute();

    // then
    assertEquals(0, IncrementCounterListener.counter);
  }

  @Test
  public void shouldSkipIoMappings() {
    // given
    ProcessDefinition processDefinition = testRule.deployAndGetDefinition(
        modify(ProcessModels.TWO_TASKS_PROCESS).activityBuilder("userTask1").camundaInputParameter("foo", "bar").done());
    ProcessInstance processInstance = runtimeService.startProcessInstanceByKey("Process");

    runtimeService.deleteProcessInstance(processInstance.getId(), "test");

    // when
    runtimeService.restartProcessInstances(processDefinition.getId())
    .startBeforeActivity("userTask1")
    .skipIoMappings()
    .processInstanceIds(processInstance.getId())
    .execute();

    // then
    Execution task1Execution = runtimeService.createExecutionQuery().activityId("userTask1").singleResult();
    assertNotNull(task1Execution);
    assertNull(runtimeService.getVariable(task1Execution.getId(), "foo"));
  }

  public static class SetVariableExecutionListenerImpl implements ExecutionListener {

    @Override
    public void notify(DelegateExecution execution) throws Exception {
      execution.setVariable("var", "foo");
    }
  }
}