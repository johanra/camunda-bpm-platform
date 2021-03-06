/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. Camunda licenses this file to you under the Apache License,
 * Version 2.0; you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.camunda.bpm.engine.impl.cmd.batch;

import org.camunda.bpm.engine.BadUserRequestException;
import org.camunda.bpm.engine.authorization.BatchPermissions;
import org.camunda.bpm.engine.batch.Batch;
import org.camunda.bpm.engine.history.HistoricProcessInstance;
import org.camunda.bpm.engine.history.HistoricProcessInstanceQuery;
import org.camunda.bpm.engine.history.UserOperationLogEntry;
import org.camunda.bpm.engine.impl.HistoricProcessInstanceQueryImpl;
import org.camunda.bpm.engine.impl.batch.builder.BatchBuilder;
import org.camunda.bpm.engine.impl.batch.BatchConfiguration;
import org.camunda.bpm.engine.impl.interceptor.Command;
import org.camunda.bpm.engine.impl.interceptor.CommandContext;
import org.camunda.bpm.engine.impl.persistence.entity.PropertyChange;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.camunda.bpm.engine.impl.util.EnsureUtil.ensureNotEmpty;

/**
 * @author Askar Akhmerov
 */
public class DeleteHistoricProcessInstancesBatchCmd implements Command<Batch> {

  protected final String deleteReason;
  protected List<String> historicProcessInstanceIds;
  protected HistoricProcessInstanceQuery historicProcessInstanceQuery;

  public DeleteHistoricProcessInstancesBatchCmd(List<String> historicProcessInstanceIds, HistoricProcessInstanceQuery historicProcessInstanceQuery,
      String deleteReason) {
    super();
    this.historicProcessInstanceIds = historicProcessInstanceIds;
    this.historicProcessInstanceQuery = historicProcessInstanceQuery;
    this.deleteReason = deleteReason;
  }

  @Override
  public Batch execute(CommandContext commandContext) {
    Collection<String> collectedInstanceIds = collectHistoricProcessInstanceIds();

    ensureNotEmpty(BadUserRequestException.class, "historicProcessInstanceIds", collectedInstanceIds);

    return new BatchBuilder(commandContext)
        .type(Batch.TYPE_HISTORIC_PROCESS_INSTANCE_DELETION)
        .config(getConfiguration(collectedInstanceIds))
        .permission(BatchPermissions.CREATE_BATCH_DELETE_FINISHED_PROCESS_INSTANCES)
        .operationLogHandler(this::writeUserOperationLog)
        .build();
  }

  protected List<String> collectHistoricProcessInstanceIds() {

    Set<String> collectedProcessInstanceIds = new HashSet<>();

    List<String> processInstanceIds = this.getHistoricProcessInstanceIds();
    if (processInstanceIds != null) {
      collectedProcessInstanceIds.addAll(processInstanceIds);
    }

    final HistoricProcessInstanceQueryImpl processInstanceQuery = (HistoricProcessInstanceQueryImpl) this.historicProcessInstanceQuery;
    if (processInstanceQuery != null) {
      for (HistoricProcessInstance hpi : processInstanceQuery.list()) {
        collectedProcessInstanceIds.add(hpi.getId());
      }
    }

    return new ArrayList<>(collectedProcessInstanceIds);
  }

  public List<String> getHistoricProcessInstanceIds() {
    return historicProcessInstanceIds;
  }

  protected void writeUserOperationLog(CommandContext commandContext, int numInstances) {
    List<PropertyChange> propertyChanges = new ArrayList<PropertyChange>();
    propertyChanges.add(new PropertyChange("nrOfInstances", null, numInstances));
    propertyChanges.add(new PropertyChange("async", null, true));
    propertyChanges.add(new PropertyChange("deleteReason", null, deleteReason));

    commandContext.getOperationLogManager()
        .logProcessInstanceOperation(UserOperationLogEntry.OPERATION_TYPE_DELETE_HISTORY,
            null,
            null,
            null,
            propertyChanges);
  }

  public BatchConfiguration getConfiguration(Collection<String> instances) {
    return new BatchConfiguration(new ArrayList<>(instances), false);
  }

}
