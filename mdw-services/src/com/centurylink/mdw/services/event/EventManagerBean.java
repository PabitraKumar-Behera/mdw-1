/*
 * Copyright (C) 2017 CenturyLink, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.centurylink.mdw.services.event;

import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.xmlbeans.XmlException;
import org.apache.xmlbeans.XmlObject;

import com.centurylink.mdw.app.ApplicationContext;
import com.centurylink.mdw.cache.impl.PackageCache;
import com.centurylink.mdw.common.MdwException;
import com.centurylink.mdw.connector.adapter.AdapterException;
import com.centurylink.mdw.connector.adapter.ConnectionException;
import com.centurylink.mdw.constant.ActivityResultCodeConstant;
import com.centurylink.mdw.constant.OwnerType;
import com.centurylink.mdw.container.ThreadPoolProvider;
import com.centurylink.mdw.dataaccess.DataAccess;
import com.centurylink.mdw.dataaccess.DataAccessException;
import com.centurylink.mdw.dataaccess.DatabaseAccess;
import com.centurylink.mdw.dataaccess.ProcessLoader;
import com.centurylink.mdw.dataaccess.RuntimeDataAccess;
import com.centurylink.mdw.event.EventHandler;
import com.centurylink.mdw.model.Response;
import com.centurylink.mdw.model.attribute.Attribute;
import com.centurylink.mdw.model.event.EventInstance;
import com.centurylink.mdw.model.event.EventLog;
import com.centurylink.mdw.model.event.EventType;
import com.centurylink.mdw.model.event.InternalEvent;
import com.centurylink.mdw.model.listener.Listener;
import com.centurylink.mdw.model.monitor.CertifiedMessage;
import com.centurylink.mdw.model.monitor.ScheduledEvent;
import com.centurylink.mdw.model.monitor.UnscheduledEvent;
import com.centurylink.mdw.model.request.Request;
import com.centurylink.mdw.model.user.UserAction;
import com.centurylink.mdw.model.user.UserAction.Action;
import com.centurylink.mdw.model.variable.Document;
import com.centurylink.mdw.model.variable.DocumentReference;
import com.centurylink.mdw.model.variable.Variable;
import com.centurylink.mdw.model.variable.VariableInstance;
import com.centurylink.mdw.model.workflow.Activity;
import com.centurylink.mdw.model.workflow.ActivityInstance;
import com.centurylink.mdw.model.workflow.Package;
import com.centurylink.mdw.model.workflow.Process;
import com.centurylink.mdw.model.workflow.ProcessInstance;
import com.centurylink.mdw.model.workflow.TransitionInstance;
import com.centurylink.mdw.model.workflow.WorkStatus;
import com.centurylink.mdw.service.data.process.EngineDataAccess;
import com.centurylink.mdw.service.data.process.EngineDataAccessDB;
import com.centurylink.mdw.service.data.process.ProcessCache;
import com.centurylink.mdw.services.EventException;
import com.centurylink.mdw.services.EventManager;
import com.centurylink.mdw.services.ProcessException;
import com.centurylink.mdw.services.messenger.InternalMessenger;
import com.centurylink.mdw.services.messenger.IntraMDWMessenger;
import com.centurylink.mdw.services.messenger.MessengerFactory;
import com.centurylink.mdw.services.pooling.AdapterConnectionPool;
import com.centurylink.mdw.services.pooling.ConnectionPoolRegistration;
import com.centurylink.mdw.services.pooling.PooledAdapterConnection;
import com.centurylink.mdw.services.process.InternalEventDriver;
import com.centurylink.mdw.services.process.ProcessExecutor;
import com.centurylink.mdw.translator.VariableTranslator;
import com.centurylink.mdw.util.TransactionWrapper;
import com.centurylink.mdw.util.log.LoggerUtil;
import com.centurylink.mdw.util.log.StandardLogger;
import com.centurylink.mdw.util.timer.CodeTimer;

public class EventManagerBean implements EventManager {

    private static StandardLogger logger = LoggerUtil.getStandardLogger();

    public void createAuditLog(UserAction userAction) throws DataAccessException, EventException {
        String name = userAction.getAction().equals(Action.Other) ? userAction.getExtendedAction() : userAction.getAction().toString();
        String comment = userAction.getDescription();
        if (userAction.getAction() == UserAction.Action.Forward)
            comment = comment == null ? userAction.getDestination() : comment + " > " + userAction.getDestination();
        String modUser = null;
        if (userAction.getAction() == UserAction.Action.Assign)
            modUser = userAction.getDestination();
        createEventLog(name, EventLog.CATEGORY_AUDIT, "User Action",
            userAction.getSource(), userAction.getEntity().toString(), userAction.getEntityId(), userAction.getUser(), modUser, comment);
    }

    /**
     * Method that creates the event log based on the passed in params
     *
     * @param pEventName
     * @param pEventCat
     * @param pEventSource
     * @param pEventOwner
     * @param pEventOwnerId
     * @return EventLog
     */
    public Long createEventLog(String pEventName, String pEventCategory, String pEventSubCat, String pEventSource,
        String pEventOwner, Long pEventOwnerId, String user, String modUser, String comments)
    throws DataAccessException, EventException {
        TransactionWrapper transaction = null;
        EngineDataAccessDB edao = new EngineDataAccessDB();
        try {
            transaction = edao.startTransaction();
            Long id = edao.recordEventLog(pEventName, pEventCategory, pEventSubCat,
                    pEventSource, pEventOwner, pEventOwnerId, user, modUser, comments);
            return id;
        } catch (SQLException e) {
            edao.rollbackTransaction(transaction);
            throw new EventException("Failed to create event log", e);
        } finally {
            edao.stopTransaction(transaction);
        }
    }

    public Integer notifyProcess(String pEventName, Long pEventInstId,
            String message, int delay)
    throws DataAccessException, EventException {
        EngineDataAccess edao = new EngineDataAccessDB();
        InternalMessenger msgBroker = MessengerFactory.newInternalMessenger();
        ProcessExecutor engine = new ProcessExecutor(edao, msgBroker, false);
        return engine.notifyProcess(pEventName, pEventInstId, message, delay);
    }

    /**
     * Method that returns distinct event log sources
     *
     * @return String[]
     */
    public String[] getDistinctEventLogEventSources()
    throws DataAccessException, EventException {
        TransactionWrapper transaction = null;
        EngineDataAccessDB edao = new EngineDataAccessDB();
        try {
            transaction = edao.startTransaction();
            return edao.getDistinctEventLogEventSources();
        } catch (SQLException e) {
            throw new EventException("Failed to notify events", e);
        } finally {
            edao.stopTransaction(transaction);
        }
    }

    public String processExternalEvent(String clsname, String request, Map<String,String> metainfo)
        throws Exception {
        String packageName = metainfo.get(Listener.METAINFO_PACKAGE_NAME);
        Package pkg = PackageCache.getPackage(packageName);
        EventHandler handler = pkg.getEventHandler(clsname, request, metainfo);
        XmlObject xmlBean = XmlObject.Factory.parse(request);
        Request requestDoc = new Request(0L);
        requestDoc.setContent(request);
        return handler.handleEventMessage(requestDoc, xmlBean, metainfo).getContent();
    }

    /**
     * Creates the process instance. This is only used by TaskManger to create
     * a dummy process instance for tasks not associated with process instances.
     *
     * @param pProcessId
     * @param pProcessOwner
     * @param pProcessOwnerId
     * @param pSecondaryOwner
     * @param pSecondaryOwnerId
     * @return new WorkInstance
     */
    public ProcessInstance createProcessInstance(Long pProcessId, String pProcessOwner,
        Long pProcessOwnerId, String pSecondaryOwner, Long pSecondaryOwnerId,
        String pMasterRequestId)
    throws ProcessException, DataAccessException {
        TransactionWrapper transaction = null;
        EngineDataAccessDB edao = new EngineDataAccessDB();
        try {
            transaction = edao.startTransaction();
            Process processVO = ProcessCache.getProcess(pProcessId);
            ProcessInstance pi = new ProcessInstance(pProcessId, processVO.getProcessName());
            pi.setOwner(pProcessOwner);
            pi.setOwnerId(pProcessOwnerId);
            pi.setSecondaryOwner(pSecondaryOwner);
            pi.setSecondaryOwnerId(pSecondaryOwnerId);
            pi.setMasterRequestId(pMasterRequestId);
            pi.setStatusCode(WorkStatus.STATUS_PENDING_PROCESS);
            edao.createProcessInstance(pi);
            return pi;
        } catch (Exception e) {
            throw new ProcessException(-1, e.getMessage(), e);
        } finally {
            edao.stopTransaction(transaction);
        }

    }

    /**
     * updates the variable instance. If the variable type is a document type,
     * this method updates the content of the document.
     *
     * @param pVarInstId
     * @param pVariableData data to be updated. If the variable is a document variable,
     *         this should be the actual content
     * @throws com.centurylink.mdw.dataaccess.DataAccessException
     */
    public void updateVariableInstance(Long pVarInstanceId, Object pVariableData)
    throws ProcessException, com.centurylink.mdw.dataaccess.DataAccessException {
        TransactionWrapper transaction = null;
        EngineDataAccessDB edao = new EngineDataAccessDB();
        try {
            transaction = edao.startTransaction();
            VariableInstance varInst = edao.getVariableInstance(pVarInstanceId);
            // do we need to check if this also looks for variables defined in ancestor processes?
            if (VariableTranslator.isDocumentReferenceVariable(varInst.getType())) {
                DocumentReference docref = (DocumentReference)varInst.getData();
                Document docvo = edao.getDocument(docref.getDocumentId(), false);
                if (pVariableData instanceof String)
                    docvo.setContent((String)pVariableData);
                else
                    docvo.setObject(pVariableData, varInst.getType());
                edao.updateDocumentContent(docvo.getDocumentId(), docvo.getContent());
            } else {
                if (pVariableData instanceof String)
                    varInst.setStringValue((String)pVariableData);
                else
                    varInst.setData(pVariableData);
                edao.updateVariableInstance(varInst);
            }
        } catch (SQLException e) {
            throw new DataAccessException(-1, "Failed to update document content", e);
        } finally {
            edao.stopTransaction(transaction);
        }
    }

    /**
     * create or update variable instance value.
     * This does not take care of checking for embedded processes.
     * For document variables, the value must be DocumentReference, not the document content
     */
    public VariableInstance setVariableInstance(Long procInstId, String name, Object value)
    throws DataAccessException {
        TransactionWrapper transaction = null;
        EngineDataAccessDB edao = new EngineDataAccessDB();
        try {
            transaction = edao.startTransaction();
            VariableInstance varInst = edao.getVariableInstance(procInstId, name);
            if (varInst != null) {
                if (value instanceof String)
                    varInst.setStringValue((String)value);
                else
                    varInst.setData(value);
                edao.updateVariableInstance(varInst);
            } else {
                if (value != null) {
                    Process processVO = ProcessCache.getProcess(edao.getProcessInstance(procInstId).getProcessId());
                    Variable variableVO = processVO.getVariable(name);
                    if (variableVO==null) {
                        throw new DataAccessException("Variable " + name + " is not defined for process " + processVO.getProcessId());
                    }

                    varInst = new VariableInstance();
                    varInst.setName(name);
                    varInst.setVariableId(variableVO.getVariableId());
                    varInst.setType(variableVO.getVariableType());
                    if (value instanceof String) varInst.setStringValue((String)value);
                    else varInst.setData(value);

                    edao.createVariableInstance(varInst, procInstId);
                } else varInst = null;
            }
            return varInst;
        } catch (SQLException e) {
            throw new DataAccessException(-1, "Failed to set variable value", e);
        } finally {
            edao.stopTransaction(transaction);
        }
    }

    public VariableInstance getVariableInstance(Long varInstId)
    throws DataAccessException {
        TransactionWrapper transaction = null;
        EngineDataAccessDB edao = new EngineDataAccessDB();
        try {
            transaction = edao.startTransaction();
            return edao.getVariableInstance(varInstId);
        } catch (SQLException e) {
            throw new DataAccessException(-1, "Fail to get variable instance", e);
        } finally {
            edao.stopTransaction(transaction);
        }
    }

    public VariableInstance getVariableInstance(Long procInstId, String name)
    throws DataAccessException {
        TransactionWrapper transaction = null;
        EngineDataAccessDB edao = new EngineDataAccessDB();
        try {
            transaction = edao.startTransaction();
            return edao.getVariableInstance(procInstId, name);
        } catch (SQLException e) {
            throw new DataAccessException(-1, "Fail to get variable instance", e);
        } finally {
            edao.stopTransaction(transaction);
        }
    }

    public List<VariableInstance> getProcessInstanceVariables(Long procInstId)
        throws DataAccessException {
        TransactionWrapper transaction = null;
        EngineDataAccessDB edao = new EngineDataAccessDB();
        try {
            transaction = edao.startTransaction();
            return edao.getProcessInstanceVariables(procInstId);
        } catch (SQLException e) {
            throw new DataAccessException(-1, "Failed to get process instance variables", e);
        } finally {
            edao.stopTransaction(transaction);
        }
    }

    public void updateProcessInstanceStatus(Long pProcInstId, Integer status)
    throws ProcessException, com.centurylink.mdw.dataaccess.DataAccessException {
        TransactionWrapper transaction = null;
        EngineDataAccessDB edao = new EngineDataAccessDB();
        try {
            transaction = edao.startTransaction();
            edao.setProcessInstanceStatus(pProcInstId, status);
            if (status.equals(WorkStatus.STATUS_COMPLETED) ||
                status.equals(WorkStatus.STATUS_CANCELLED) ||
                status.equals(WorkStatus.STATUS_FAILED)) {
                edao.removeEventWaitForProcessInstance(pProcInstId);
            }
        } catch (SQLException e) {
            throw new ProcessException(0, "Failed to update process instance status", e);
        } finally {
            edao.stopTransaction(transaction);
        }
    }

    /**
     * This is for regression tester only.
     * @param masterRequestId
     */
    public void sendDelayEventsToWaitActivities(String masterRequestId)
            throws DataAccessException, ProcessException {
        TransactionWrapper transaction = null;
        EngineDataAccessDB edao = new EngineDataAccessDB();
        try {
            transaction = edao.startTransaction();
            List<ProcessInstance> procInsts = edao.getProcessInstancesByMasterRequestId(masterRequestId, null);
            for (ProcessInstance pi : procInsts) {
                List<ActivityInstance> actInsts = edao.getActivityInstancesForProcessInstance(pi.getId());
                for (ActivityInstance ai : actInsts) {
                    if (ai.getStatusCode()==WorkStatus.STATUS_WAITING.intValue()) {
                        InternalEvent event = InternalEvent.createActivityDelayMessage(ai,
                                masterRequestId);
                        this.sendInternalEvent(event, edao);
                    }
                }
            }
        } catch (SQLException e) {
            throw new ProcessException(0, "Failed to send delay event wait activities runtime", e);
        } finally {
            edao.stopTransaction(transaction);
        }
    }


    /**
     * Sends the JMS Message to the EventHandler
     *
     * @param pMsg Message to be send out
     * @param pDelay delay the delivery of the message in seconds
     * @throws ProcessException
     */
    public void sendInternalEvent(String message) throws ProcessException {
        TransactionWrapper transaction = null;
        EngineDataAccessDB edao = new EngineDataAccessDB();
        try {
            transaction = edao.startTransaction();
            this.sendInternalEvent(new InternalEvent(message), edao);
        } catch (DataAccessException e) {
            throw new ProcessException(0, "Failed to send internal message", e);
        } catch (XmlException e) {
            throw new ProcessException(0, "Failed to send internal message", e);
        } finally {
            try {
                edao.stopTransaction(transaction);
            } catch (DataAccessException e) {
                throw new ProcessException(0, "Failed to send internal message", e);
            }
        }
    }

    private void sendInternalEvent(InternalEvent pMsg, EngineDataAccess edao) throws ProcessException {
        InternalMessenger msgbroker = MessengerFactory.newInternalMessenger();
        msgbroker.sendMessage(pMsg, edao);
    }

    public void retryActivity(Long activityId, Long activityInstId)
    throws DataAccessException, ProcessException {
        CodeTimer timer = new CodeTimer("WorkManager.retryActivity", true);
        TransactionWrapper transaction = null;
        EngineDataAccessDB edao = new EngineDataAccessDB();
        try {
            transaction = edao.startTransaction();
            ActivityInstance ai = edao.getActivityInstance(activityInstId);
            Long procInstId = ai.getProcessInstanceId();
            ProcessInstance pi = edao.getProcessInstance(procInstId);
            if (!this.isProcessInstanceResumable(pi)) {
                logger.info("ProcessInstance in NOT resumable. ProcessInstanceId:" + pi.getId());
                timer.stopAndLogTiming("NotResumable");
                throw new ProcessException("The process instance is not resumable");
            }
            InternalEvent outgoingMsg = InternalEvent.createActivityStartMessage(
                    activityId, procInstId, null, pi.getMasterRequestId(), ActivityResultCodeConstant.RESULT_RETRY);
            edao.setProcessInstanceStatus(pi.getId(), WorkStatus.STATUS_IN_PROGRESS);
            this.sendInternalEvent(outgoingMsg, edao);
        } catch (SQLException e) {
            throw new ProcessException(0, "Failed to remove event waits", e);
        } catch (MdwException e) {
            throw new ProcessException(0, "Failed to remove event waits", e);
        } finally {
            edao.stopTransaction(transaction);
        }
        timer.stopAndLogTiming("");
    }

    public void skipActivity(Long activityId, Long activityInstId, String completionCode)
    throws DataAccessException, ProcessException {
        CodeTimer timer = new CodeTimer("WorkManager.skipActivity", true);
        TransactionWrapper transaction = null;
        EngineDataAccessDB edao = new EngineDataAccessDB();
        try {
            transaction = edao.startTransaction();
            ActivityInstance ai = edao.getActivityInstance(activityInstId);
            Long procInstId = ai.getProcessInstanceId();
            ProcessInstance pi = edao.getProcessInstance(procInstId);
            if (!this.isProcessInstanceResumable(pi)) {
                logger.info("ProcessInstance in NOT resumable. ProcessInstanceId:" + pi.getId());
                timer.stopAndLogTiming("NotResumable");
                throw new ProcessException("The process instance is not resumable");
            }

            Integer eventType;
            if (completionCode!=null) {
                int k = completionCode.indexOf(':');
                if (k<0) {
                    eventType = EventType.getEventTypeFromName(completionCode);
                    if (eventType!=null) completionCode = null;
                    else {
                        if (completionCode.length()==0) completionCode = null;
                        eventType = EventType.FINISH;
                    }
                } else {
                    String eventName = completionCode.substring(0,k);
                    eventType = EventType.getEventTypeFromName(eventName);
                    if (eventType!=null) {
                        completionCode = completionCode.substring(k+1);
                        if (completionCode.length()==0) completionCode = null;
                    } else eventType = EventType.FINISH;
                }
            } else {
                eventType = EventType.FINISH;
            }

            InternalEvent outgoingMsg = InternalEvent.
                createActivityNotifyMessage(ai, eventType,
                        pi.getMasterRequestId(), completionCode);
            this.sendInternalEvent(outgoingMsg, edao);
            edao.setProcessInstanceStatus(pi.getId(), WorkStatus.STATUS_IN_PROGRESS);
        } catch (SQLException e) {
            throw new ProcessException(0, "Failed to remove event waits", e);
        } catch (MdwException e) {
            throw new ProcessException(0, "Failed to remove event waits", e);
        } finally {
            edao.stopTransaction(transaction);
        }
        timer.stopAndLogTiming("");
    }

    /**
     * Checks if the process inst is resumable
     *
     * @param pInstance
     * @return boolean status
     */
    private boolean isProcessInstanceResumable(ProcessInstance pInstance) {

        int statusCd = pInstance.getStatusCode().intValue();
        if (statusCd == WorkStatus.STATUS_COMPLETED.intValue()) {
            return false;
        } else if (statusCd == WorkStatus.STATUS_CANCELLED.intValue()) {
            return false;
        }
        return true;
    }

    /**
     * Returns the ProcessInstance identified by the passed in Id
     *
     * @param pProcInstId
     * @return ProcessInstance
     */
    public ProcessInstance getProcessInstance(Long procInstId)
    throws ProcessException, DataAccessException {
        TransactionWrapper transaction = null;
        EngineDataAccessDB edao = new EngineDataAccessDB();
        try {
            transaction = edao.startTransaction();
            return edao.getProcessInstance(procInstId);

        } catch (SQLException e) {
            throw new ProcessException(0, "Failed to get process instance", e);
        } finally {
            edao.stopTransaction(transaction);
        }
    }

    /**
     * Returns the ProcessInstance and associated variable  identified by the passed in Id
     *
     * @param pProcInstId
     * @param loadVariables
     * @return ProcessInstance
     */
    public ProcessInstance getProcessInstance(Long procInstId, boolean loadVariables)
    throws ProcessException, DataAccessException {
        if (loadVariables)
        {
            ProcessInstance processInstanceVO = this.getProcessInstance(procInstId);
            List<VariableInstance> variables =  this.getProcessInstanceVariables(procInstId);
            processInstanceVO.setVariables(variables);
            return processInstanceVO;
        }
        return this.getProcessInstance(procInstId);
    }
    /**
     * Returns the process instances by process name and master request ID.
     *
     * @param processName
     * @param masterRequestId
     * @return the list of process instances. If the process definition is not found, null
     *         is returned; if process definition is found but no process instances are found,
     *         an empty list is returned.
     * @throws ProcessException
     * @throws DataAccessException
     */
    public List<ProcessInstance> getProcessInstances(String masterRequestId, String processName)
    throws ProcessException, DataAccessException {
        TransactionWrapper transaction = null;
        EngineDataAccessDB edao = new EngineDataAccessDB();
        try {
            Process procdef = ProcessCache.getProcess(processName, 0);
            if (procdef==null) return null;
            transaction = edao.startTransaction();
            return edao.getProcessInstancesByMasterRequestId(masterRequestId, procdef.getProcessId());
        } catch (SQLException e) {
            throw new ProcessException(0, "Failed to remove event waits", e);
        } finally {
            edao.stopTransaction(transaction);
        }
    }

    /**
     * Returns the activity instances by process name, activity logical ID, and  master request ID.
     *
     * @param processName
     * @param activityLogicalId
     * @param masterRequestId
     * @return the list of activity instances. If the process definition or the activity
     *         with the given logical ID is not found, null
     *         is returned; if process definition is found but no process instances are found,
     *         or no such activity instances are found, an empty list is returned.
     * @throws ProcessException
     * @throws DataAccessException
     */
    public List<ActivityInstance> getActivityInstances(String masterRequestId, String processName, String activityLogicalId)
    throws ProcessException, DataAccessException {
        TransactionWrapper transaction = null;
        EngineDataAccessDB edao = new EngineDataAccessDB();
        try {
            Process procdef = ProcessCache.getProcess(processName, 0);
            if (procdef==null) return null;
            Activity actdef = procdef.getActivityByLogicalId(activityLogicalId);
            if (actdef==null) return null;
            transaction = edao.startTransaction();
            List<ActivityInstance> actInstList = new ArrayList<ActivityInstance>();
            List<ProcessInstance> procInstList =
                edao.getProcessInstancesByMasterRequestId(masterRequestId, procdef.getProcessId());
            if (procInstList.size()==0) return actInstList;
            for (ProcessInstance pi : procInstList) {
                List<ActivityInstance> actInsts = edao.getActivityInstances(actdef.getActivityId(), pi.getId(), false, false);
                for (ActivityInstance ai : actInsts) {
                    actInstList.add(ai);
                }
            }
            return actInstList;
        } catch (SQLException e) {
            throw new ProcessException(0, "Failed to remove event waits", e);
        } finally {
            edao.stopTransaction(transaction);
        }
    }

    /**
     * Returns the ActivityInstance identified by the passed in Id
     *
     * @param pActivityInstId
     * @return ActivityInstance
     */
    public ActivityInstance getActivityInstance(Long pActivityInstId)
    throws ProcessException, DataAccessException {
        ActivityInstance ai;
        TransactionWrapper transaction = null;
        EngineDataAccessDB edao = new EngineDataAccessDB();
        try {
            transaction = edao.startTransaction();
            ai = edao.getActivityInstance(pActivityInstId);
        } catch (SQLException e) {
            throw new ProcessException(0, "Failed to get activity instance", e);
        } finally {
            edao.stopTransaction(transaction);
        }
        return ai;
    }

    /**
     * Returns the process instance based on the passed in params
     *
     * @param pProcessId Unique Identifier for the process
     * @param pOwner Owner of the process instance
     * @param pOwnerId Unique Id for the owner
     * @return ProcessInstance object
     */
    public List<ProcessInstance> getProcessInstances(Long pProcessId, String pOwner, Long pOwnerId)
    throws ProcessException, DataAccessException {
        TransactionWrapper transaction = null;
        EngineDataAccessDB edao = new EngineDataAccessDB();
        try {
            transaction = edao.startTransaction();
            return edao.getProcessInstances(pProcessId, pOwner, pOwnerId);
        } catch (SQLException e) {
            throw new ProcessException(0, "Failed to remove event waits", e);
        } finally {
            edao.stopTransaction(transaction);
        }
    }

    /**
     * Returns the WorkTransitionVO based on the passed in params
     *
     * @param pId
     * @return WorkTransitionINstance
     */
    public TransitionInstance getWorkTransitionInstance(Long pId)
    throws DataAccessException, ProcessException {
        TransitionInstance wti;
        TransactionWrapper transaction = null;
        EngineDataAccessDB edao = new EngineDataAccessDB();
        try {
            transaction = edao.startTransaction();
            wti = edao.getWorkTransitionInstance(pId);
        } catch (SQLException e) {
            throw new ProcessException(0, "Failed to get work transition instance", e);
        } finally {
            edao.stopTransaction(transaction);
        }
        return wti;
    }

    public void updateDocumentContent(Long docid, Object doc, String type)
    throws DataAccessException {
        updateDocumentContent(docid, doc, type, null);
    }

    public void updateDocumentContent(Long docid, Object doc, String type, Package pkg)
    throws DataAccessException {
        TransactionWrapper transaction = null;
        EngineDataAccessDB edao = new EngineDataAccessDB();
        try {
            transaction = edao.startTransaction();
            Document docvo = edao.getDocument(docid, false);
            if (doc instanceof String) docvo.setContent((String)doc);
            else docvo.setObject(doc, type);
            edao.updateDocumentContent(docvo.getDocumentId(), docvo.getContent(pkg));
        } catch (SQLException e) {
            throw new DataAccessException(-1, "Failed to update document content", e);
        } finally {
            edao.stopTransaction(transaction);
        }
    }

    public void updateDocumentInfo(Long docid, String documentType,
            String ownerType, Long ownerId) throws DataAccessException {
        TransactionWrapper transaction = null;
        EngineDataAccessDB edao = new EngineDataAccessDB();
        try {
            transaction = edao.startTransaction();
            Document docvo = edao.getDocument(docid, false);
            if (documentType != null)
                docvo.setDocumentType(documentType);
            if (ownerType != null)
                docvo.setOwnerType(ownerType);
            if (ownerId != null)
                docvo.setOwnerId(ownerId);
            edao.updateDocumentInfo(docvo);
        }
        catch (SQLException e) {
            throw new DataAccessException(-1, "Failed to update document content", e);
        }
        finally {
            edao.stopTransaction(transaction);
        }
    }

    public Long createDocument(String type, String ownerType, Long ownerId, Object doc)
    throws DataAccessException {
        return createDocument(type, ownerType, ownerId, doc, null);
    }

    public Long createDocument(String type, String ownerType, Long ownerId, Object doc, Package pkg)
    throws DataAccessException {
        TransactionWrapper transaction = null;
        EngineDataAccessDB edao = new EngineDataAccessDB();
        try {
            transaction = edao.startTransaction();
            Document docvo = new Document();
            if (doc instanceof Response) {
                String statusMsg = ((Response)doc).getStatusMessage() != null ? ((Response)doc).getStatusMessage() : "";
                docvo.setStatusCode(((Response)doc).getStatusCode());
                docvo.setStatusMessage(statusMsg.length() > 1000 ? statusMsg.substring(0, 1000) : statusMsg);
                docvo.setContent(((Response)doc).getContent());
            }
            else if (doc instanceof String)
                docvo.setContent((String)doc);
            else
                docvo.setObject(doc, type);
            docvo.setDocumentType(type);
            docvo.setOwnerType(ownerType);
            docvo.setOwnerId(ownerId);
            Long docid = edao.createDocument(docvo, pkg);
            return docid;
        } catch (SQLException e) {
            throw new DataAccessException(-1, "Failed to create document", e);
        } finally {
            edao.stopTransaction(transaction);
        }
    }

    public Document getDocumentVO(Long docid)
    throws DataAccessException {
        try {
            DatabaseAccess db = new DatabaseAccess(null);
            RuntimeDataAccess da = DataAccess.getRuntimeDataAccess(db);
            return da.getDocument(docid);
        } catch (Exception ex) {
            throw new DataAccessException(-1, ex.getMessage(), ex);
        }
    }

    /**
     * Builds the value object that represents a process
     *
     * @param pProcessId
     * @return ProcessVO
     */
    public Process getProcess(Long id)
    throws DataAccessException, ProcessException {
        CodeTimer timer = new CodeTimer("getProcessVO()", true);
        ProcessLoader loader = DataAccess.getProcessLoader();
        Process processVO;
        try {
            processVO = loader.loadProcess(id, true);
            if (processVO != null) {
                // all db attributes are override attributes
                Map<String,String> attributes = getAttributes(OwnerType.PROCESS, id);
                if (attributes != null)
                    processVO.applyOverrideAttributes(attributes);
            }
        } catch (com.centurylink.mdw.dataaccess.DataAccessException e) {
            throw new DataAccessException(0, "Cannot load process ID: " + id + " (" + e.getMessage() + ")", e);
        }
        timer.stopAndLogTiming("");
        return processVO;
    }

    /**
     * Builds the value object that represents a process
     *
     * @param pProcessName
     * @param pVersion
     * @return ProcessVO
     */
    public Process getProcess(String procname, int version)
    throws DataAccessException {
        CodeTimer timer = new CodeTimer("getProcessVO()", true);
        Process processVO = null;
        try {
            processVO = DataAccess.getProcessLoader().getProcessBase(procname, version);
            if (processVO != null) {
                // all db attributes are override attributes
                Map<String,String> attributes = getAttributes(OwnerType.PROCESS, processVO.getProcessId());
                if (attributes != null)
                    processVO.applyOverrideAttributes(attributes);
            }
        } catch (Exception e) {
            throw new DataAccessException(0, "Cannot load process: " + procname + " v" + version + " (" + e.getMessage() + ")", e);
        }
        timer.stopAndLogTiming("");
        return processVO;
    }

    public List<EventLog> getEventLogs(String pEventName, String pEventSource,
            String pEventOwner, Long pEventOwnerId) throws DataAccessException {
        try {
            DatabaseAccess db = new DatabaseAccess(null);
            RuntimeDataAccess da = DataAccess.getRuntimeDataAccess(db);
            return da.getEventLogs(pEventName, pEventSource, pEventOwner, pEventOwnerId);
        } catch (Exception ex) {
            throw new DataAccessException(-1, ex.getMessage(), ex);
        }
    }

    public EventInstance getEventInstance(String eventName) throws DataAccessException {
        TransactionWrapper transaction = null;
        EngineDataAccessDB edao = new EngineDataAccessDB();
        try {
            transaction = edao.startTransaction();
            return edao.getEventInstance(eventName);
        } catch (SQLException e) {
            throw new DataAccessException(-1, "Failed to get event instance", e);
        } finally {
            edao.stopTransaction(transaction);
        }
    }

    public List<ScheduledEvent> getScheduledEventList(Date cutoffTime) throws DataAccessException {
        TransactionWrapper transaction = null;
        EngineDataAccessDB edao = new EngineDataAccessDB();
        try {
            transaction = edao.startTransaction();
            return edao.getScheduledEventList(cutoffTime);
        } catch (SQLException e) {
            throw new DataAccessException(-1, "Failed to get scheduled event list", e);
        } finally {
            edao.stopTransaction(transaction);
        }
    }

    public List<UnscheduledEvent> getUnscheduledEventList(Date olderThan, int batchSize) throws DataAccessException {
        TransactionWrapper transaction = null;
        EngineDataAccessDB edao = new EngineDataAccessDB();
        try {
            transaction = edao.startTransaction();
            return edao.getUnscheduledEventList(olderThan, batchSize);
        } catch (SQLException e) {
            throw new DataAccessException(-1, "Failed to get unscheduled event list", e);
        } finally {
            edao.stopTransaction(transaction);
        }
    }

    public void offerScheduledEvent(ScheduledEvent event)
    throws DataAccessException {
        TransactionWrapper transaction = null;
        EngineDataAccessDB edao = new EngineDataAccessDB();
        try {
            transaction = edao.startTransaction();
            edao.offerScheduledEvent(event);
        } catch (SQLIntegrityConstraintViolationException e) {
            throw new DataAccessException(23000, "The event is already scheduled", e);
        } catch (SQLException e) {
            if (e.getSQLState().equals("23000")) {
                throw new DataAccessException(23000, "The event is already scheduled", e);
                // for unknown reason (may be because of different Oracle driver - ojdbc14),
                // when running under Tomcat, contraint violation does not throw SQLIntegrityConstraintViolationException
                // 23000 is ANSI/SQL standard SQL State for constraint violation
                // Alternatively, we can use e.getErrorCode()==1 for Oracle (ORA-00001)
                // or e.getErrorCode()==1062 for MySQL
            } else {
                throw new DataAccessException(-1, "Failed to create scheduled event", e);
            }
        } finally {
            edao.stopTransaction(transaction);
        }
    }

    public void processScheduledEvent(String eventName, Date now)
    throws DataAccessException {
        TransactionWrapper transaction = null;
        EngineDataAccessDB edao = new EngineDataAccessDB();
        try {
            transaction = edao.startTransaction();
            ScheduledEvent event = edao.lockScheduledEvent(eventName);
            Date currentScheduledTime = event==null?null:event.getScheduledTime();
            ScheduledEventQueue queue = ScheduledEventQueue.getSingleton();
            boolean processed = queue.processEventInEjb(eventName, event, now, edao);
            if (processed)  {
                if (event.isScheduledJob()) {
                    edao.recordScheduledJobHistory(event.getName(), currentScheduledTime,
                            ApplicationContext.getServerHostPort());
                }
                if (event.getScheduledTime()==null) edao.deleteEventInstance(event.getName());
                else edao.updateEventInstance(event.getName(), null, null,
                        event.getScheduledTime(), null, null, 0, null);
            }     // else do nothing - may be processed by another server
        } catch (SQLException e) {
            throw new DataAccessException(-1, "Failed to process scheduled event", e);
        } finally {
            edao.stopTransaction(transaction);
        }
    }

    public boolean processUnscheduledEvent(String eventName) {
        TransactionWrapper transaction = null;
        boolean processed = false;
        EngineDataAccessDB edao = new EngineDataAccessDB();
        try {
            transaction = edao.startTransaction();
            ScheduledEvent event = edao.lockScheduledEvent(eventName);
            if (event!=null) {
                ThreadPoolProvider thread_pool = ApplicationContext.getThreadPoolProvider();
                InternalEventDriver command = new InternalEventDriver(null, event.getMessage());
                if (thread_pool.execute(ThreadPoolProvider.WORKER_SCHEDULER, event.getName(), command)) {
                    String query = "delete from EVENT_INSTANCE where EVENT_NAME=?";
                    transaction.getDatabaseAccess().runUpdate(query, event.getName());
                    processed = true;
                }
            }
        } catch (Exception e) {
            logger.severeException("Failed to process unscheduled event " + eventName, e);
            // do not rollback - that may cause the event being processed again and again
            processed = false;
        } finally {
            try {
                edao.stopTransaction(transaction);
            } catch (DataAccessException e) {
                logger.severeException("Failed to process unscheduled event " + eventName, e);
                // do not rollback - that may cause the event being processed again and again
                processed = false;
            }
        }
        return processed;
        // return true when the message is successfully sent; when false, release reserved connection
    }

    public List<UnscheduledEvent> processInternalEvents(List<UnscheduledEvent> eventList) {
        List<UnscheduledEvent> returnList = new ArrayList<UnscheduledEvent>();
        ThreadPoolProvider thread_pool = ApplicationContext.getThreadPoolProvider();
        for (UnscheduledEvent one : eventList) {
            if (EventInstance.ACTIVE_INTERNAL_EVENT.equals(one.getReference())) {
                InternalEventDriver command = new InternalEventDriver(one.getName(), one.getMessage());
                if (!thread_pool.execute(ThreadPoolProvider.WORKER_SCHEDULER, one.getName(), command)) {
                    String msg = ThreadPoolProvider.WORKER_SCHEDULER + " has no thread available for Unscheduled event: " + one.getName() + " message:\n" + one.getMessage();
                    // make this stand out
                    logger.warnException(msg, new Exception(msg));
                    logger.info(thread_pool.currentStatus());
                    returnList.add(one);
                }
            }
            else
                returnList.add(one);
        }
        return returnList;
    }


    public List<CertifiedMessage> getCertifiedMessageList()
    throws DataAccessException {
        TransactionWrapper transaction = null;
        EngineDataAccessDB edao = new EngineDataAccessDB();
        try {
            transaction = edao.startTransaction();
            return edao.getCertifiedMessageList();
        } catch (SQLException e) {
            throw new DataAccessException(-1, "Failed to update document content", e);
        } finally {
            edao.stopTransaction(transaction);
        }
    }

    public void recordCertifiedMessage(CertifiedMessage message)
    throws DataAccessException {
        TransactionWrapper transaction = null;
        EngineDataAccessDB edao = new EngineDataAccessDB();
        try {
            transaction = edao.startTransaction();
            edao.recordCertifiedMessage(message);
        } catch (SQLException e) {
            throw new DataAccessException(-1, "Failed to process delayed event", e);
        } finally {
            edao.stopTransaction(transaction);
        }
    }

    /**
     *
     * @param message
     * @param ackTimeout timeout in seconds for waiting for acknowledgment
     * @param maxTries maximum number of tries
     * @param retryInterval elapse time in seconds for the next retry
     * @return true when retry is needed, false o/w
     * @throws DataAccessException
     */
    public boolean deliverCertifiedMessage(CertifiedMessage message, int ackTimeout, int maxTries,
            int retryInterval) {
        TransactionWrapper transaction = null;
        EngineDataAccessDB edao = new EngineDataAccessDB();
        try {
            transaction = edao.startTransaction();
            // 1. lock the certified message
            CertifiedMessage refreshed;
            try {
                refreshed = edao.lockCertifiedMessage(message.getId());
            } catch (SQLException e) {
                logger.severe("Failed to lock certified message " + message.getId() + ", retry again");
                message.setNextTryTime(new Date(DatabaseAccess.getCurrentTime()+1000*retryInterval));
                return true;
            }
            // 2. handle the case when the message has been deleted (only possible to delete from admin
            if (refreshed==null) {
                // only possible when the message is deleted from admin
                message.setStatus(EventInstance.STATUS_CERTIFIED_MESSAGE_CANCEL);
                return false;
            }
            // 3. handle the case when the message has been processed by another managed server
            int count = refreshed.getTryCount();
            if (!refreshed.getStatus().equals(EventInstance.STATUS_CERTIFIED_MESSAGE)) return false;
            if (count!=message.getTryCount()) {
                // the message has been retried by some other server, successful or failed
                message.setTryCount(count);
                if (refreshed.getNextTryTime()!=null) {
                    message.setNextTryTime(refreshed.getNextTryTime());
                    return true;
                } else return false;
            }
            // 4. load message content if not already in memory
            if (message.getContent()==null) {
                try {
                    Document docvo = this.getDocumentVO(message.getDocumentId());
                    message.setContent(docvo.getContent());
                } catch (DataAccessException e) {
                    message.setStatus(EventInstance.STATUS_CERTIFIED_MESSAGE_HOLD);
                    logger.severeException(LoggerUtil.getStandardLogger().getSentryMark()+
                            "Failed to load certified message content: " + message.getId(), e);
                    updateCertifiedMessageStatus(edao, message.getId(), message.getStatus(), 0,
                            new Date(DatabaseAccess.getCurrentTime()));
                    return false;
                }
            }
            int exceptionCode = 0;
            String protocol = message.getProperty(CertifiedMessage.PROP_PROTOCOL);
            PooledAdapterConnection conn = null;
            try {
                if (CertifiedMessage.PROTOCOL_POOL.equals(protocol)) {
                    // 5a. handle connection pool based certified messages
                    String pool_name = message.getProperty(CertifiedMessage.PROP_POOL_NAME);
                    if (pool_name==null) throw new AdapterException("Pool name is not specified");
                    AdapterConnectionPool pool = ConnectionPoolRegistration.getPool(pool_name);
                    if (pool==null) throw new AdapterException("Connection pool is not configured: " + pool_name);
                    conn = pool.getConnection("Certified Message Manager", null);
                    conn.invoke(message.getContent(), ackTimeout, message.getProperties());
                } else {
                    // 5b. handle JMS based certified messages
                    String jndi = message.getProperty(CertifiedMessage.PROP_JNDI_URL);
                    IntraMDWMessenger messenger = MessengerFactory.newIntraMDWMessenger(jndi);
                    messenger.sendCertifiedMessage(message.getContent(), message.getId(), ackTimeout);
                }
                // 6a. mark message delivered
                message.setStatus(EventInstance.STATUS_CERTIFIED_MESSAGE_DELIVERED);
                updateCertifiedMessageStatus(edao, message.getId(), message.getStatus(), 0,
                        new Date(DatabaseAccess.getCurrentTime()));
                return false;
            } catch (AdapterException e1) {
                // 6b. handle non-retriable errors
                exceptionCode = ((AdapterException)e1).getCode();
                message.setStatus(EventInstance.STATUS_CERTIFIED_MESSAGE_HOLD);
                logger.severeException(LoggerUtil.getStandardLogger().getSentryMark()+ "Certified message hits unretriable error: "
                            + message.getId(), e1);
                updateCertifiedMessageStatus(edao, message.getId(), message.getStatus(), 0,
                        new Date(DatabaseAccess.getCurrentTime()));
                return false;
            } catch (Exception e1) {
                // 6c. handle retriable errors
                // this includes the case for NoSuchElementException - pool connections are exhausted
                String exceptionName = e1.getClass().getName();
                if (e1 instanceof ConnectionException) {
                    exceptionCode = ((ConnectionException)e1).getCode();
                    exceptionName += ":" + exceptionCode;
                }
                if (count>0) {
                    logger.severe("Failed to deliver certified message " + message.getId()
                            + ", exception " + exceptionName
                            + " - retry " + count);
                } else {
                    logger.severeException("Failed to deliver certified message " + message.getId(), e1);
                }
                if (exceptionCode==ConnectionException.POOL_DISABLED) {
                    logger.severe("Pool is disabled - indefinite wait for certified message " + message.getId());
                    updateCertifiedMessageStatus(edao, message.getId(), message.getStatus(), 0, null);
                    return false;
                } else if (count>=maxTries) {
                    // 7a. when exceeding max retry limit
                    message.setStatus(EventInstance.STATUS_CERTIFIED_MESSAGE_HOLD);
                    logger.severe(LoggerUtil.getStandardLogger().getSentryMark() +
                            "Certified message failed to deliver after max tries: " + message.getId());
                    updateCertifiedMessageStatus(edao, message.getId(), message.getStatus(), 0,
                            new Date(DatabaseAccess.getCurrentTime()));
                    return false;
                } else {
                    // 7b. when retry count is within limit, really retrying
                    message.setTryCount(count+1);
                    message.setNextTryTime(new Date(DatabaseAccess.getCurrentTime()+1000*retryInterval));
                    updateCertifiedMessageStatus(edao, message.getId(),
                            EventInstance.STATUS_CERTIFIED_MESSAGE, message.getTryCount(), message.getNextTryTime());
                    return true;
                }
            } finally {
                if (exceptionCode==0) exceptionCode = -1;
                if (conn!=null) conn.returnConnection(exceptionCode);
            }
        } catch (DataAccessException e) {
            logger.severe("Failed to start transaction for delivering certified message " + message.getId() + ", retry again");
            message.setNextTryTime(new Date(DatabaseAccess.getCurrentTime()+1000*retryInterval));
            return true;
        } finally {
            try {
                edao.stopTransaction(transaction);
            } catch (DataAccessException e) {
                logger.severeException("Fail to commit transaction", e);
            }
        }
    }

    private void updateCertifiedMessageStatus(EngineDataAccessDB edao, String msgid,
            Integer status, int tryCount, Date consumeTime) {
        try {
            edao.updateCertifiedMessageStatus(msgid, status, tryCount, consumeTime);
        } catch (SQLException e) {
            logger.severeException("Failed to update certified message status in database", e);
        }
    }

    public boolean consumeCertifiedMessage(String messageId)
    throws DataAccessException {
        TransactionWrapper transaction = null;
        EngineDataAccessDB edao = new EngineDataAccessDB();
        try {
            transaction = edao.startTransaction();
            edao.consumeCertifiedMessage(messageId);
            return true;
        } catch (SQLIntegrityConstraintViolationException e) {
            return false;
        } catch (SQLException e) {
            if (e.getSQLState().equals("23000")) return false;
            throw new DataAccessException(-1, "Failed to consume certified message", e);
        } finally {
            edao.stopTransaction(transaction);
        }
    }

    public void updateCertifiedMessageStatus(String msgid, Integer status)
    throws DataAccessException {
        TransactionWrapper transaction = null;
        EngineDataAccessDB edao = new EngineDataAccessDB();
        try {
            transaction = edao.startTransaction();
            edao.updateCertifiedMessageStatus(msgid, status, 0,
                    new Date(DatabaseAccess.getCurrentTime()));
        } catch (Exception e) {
            throw new DataAccessException(-1, e.getMessage(), e);
        } finally {
            edao.stopTransaction(transaction);
        }
    }

    public int getTableRowCount(String tableName, String whereClause) throws DataAccessException {
        TransactionWrapper transaction = null;
        EngineDataAccessDB edao = new EngineDataAccessDB();
        try {
            transaction = edao.startTransaction();
            return edao.getTableRowCount(tableName, whereClause);
        } catch (SQLException e) {
            throw new DataAccessException(-1, "Failed to obtain event instance count", e);
        } finally {
            edao.stopTransaction(transaction);
        }
    }

    public List<String[]> getTableRowList(String tableName, Class<?>[] types, String[] fields,
            String whereClause, String orderby, boolean descending, int startRow, int rowCount)
    throws DataAccessException {
        TransactionWrapper transaction = null;
        EngineDataAccessDB edao = new EngineDataAccessDB();
        try {
            transaction = edao.startTransaction();
            return edao.getTableRowList(tableName, types, fields, whereClause, orderby, descending, startRow, rowCount);
        } catch (SQLException e) {
            throw new DataAccessException(-1, "Failed to retrieve event instance list", e);
        } finally {
            edao.stopTransaction(transaction);
        }
    }

    public int deleteTableRow(String tableName, String fieldName, Object fieldValue) throws DataAccessException {
        TransactionWrapper transaction = null;
        EngineDataAccessDB edao = new EngineDataAccessDB();
        try {
            transaction = edao.startTransaction();
            return edao.deleteTableRow(tableName, fieldName, fieldValue);
        } catch (SQLException e) {
            throw new DataAccessException(-1, "Failed to delete " + fieldValue.toString() + " from " + tableName, e);
        } finally {
            edao.stopTransaction(transaction);
        }
    }

    public void createEventInstance(String eventName,
            Long documentId, Integer status, Date consumeDate, String auxdata, String reference, int preserveSeconds)
    throws DataAccessException {
        TransactionWrapper transaction = null;
        EngineDataAccessDB edao = new EngineDataAccessDB();
        try {
            transaction = edao.startTransaction();
            edao.createEventInstance(eventName, documentId, status, consumeDate, auxdata, reference, preserveSeconds);
        } catch (SQLIntegrityConstraintViolationException e) {
            throw new DataAccessException(23000, "The event instance already exists", e);
        } catch (SQLException e) {
            if (e.getSQLState().equals("23000"))
                throw new DataAccessException(23000, "The event is already scheduled", e);
            else throw new DataAccessException(-1, "Failed to retrieve event instance list", e);
        } finally {
            edao.stopTransaction(transaction);
        }
    }

    public void updateEventInstance(String eventName,
            Long documentId, Integer status, Date consumeDate, String auxdata, String reference, int preserveSeconds)
    throws DataAccessException {
        updateEventInstance(eventName, documentId, status, consumeDate, auxdata, reference, preserveSeconds, null);
    }

    public void updateEventInstance(String eventName,
            Long documentId, Integer status, Date consumeDate, String auxdata, String reference, int preserveSeconds, String comments)
    throws DataAccessException {
        TransactionWrapper transaction = null;
        EngineDataAccessDB edao = new EngineDataAccessDB();
        try {
            transaction = edao.startTransaction();
            edao.updateEventInstance(eventName, documentId, status, consumeDate, auxdata, reference, preserveSeconds, comments);
        } catch (SQLException e) {
            throw new DataAccessException(-1, "Failed to retrieve event instance list", e);
        } finally {
            edao.stopTransaction(transaction);
        }
    }

    public void createEventWaitInstance(String eventName,
            Long actInstId,  String compCode)
    throws DataAccessException {
        TransactionWrapper transaction = null;
        EngineDataAccessDB edao = new EngineDataAccessDB();
        try {
            transaction = edao.startTransaction();
            edao.createEventWaitInstance(actInstId, eventName, compCode);
        } catch (SQLException e) {
            throw new DataAccessException(-1, "Failed to create event wait instance", e);
        } finally {
            edao.stopTransaction(transaction);
        }
    }

    public void createTableRow(String tableName, String[] fieldNames, Object[] fieldValues)
        throws DataAccessException {
        TransactionWrapper transaction = null;
        EngineDataAccessDB edao = new EngineDataAccessDB();
        try {
            transaction = edao.startTransaction();
            edao.createTableRow(tableName, fieldNames, fieldValues);
        } catch (SQLException e) {
            throw new DataAccessException(-1, "Failed to create entry in " + tableName, e);
        } finally {
            edao.stopTransaction(transaction);
        }
    }

    public int updateTableRow(String tableName, String keyName, Object keyValue,
            String[] fieldNames, Object[] fieldValues)  throws DataAccessException {
        TransactionWrapper transaction = null;
        EngineDataAccessDB edao = new EngineDataAccessDB();
        try {
            transaction = edao.startTransaction();
            return edao.updateTableRow(tableName, keyName, keyValue, fieldNames, fieldValues);
        } catch (SQLException e) {
            throw new DataAccessException(-1, "Failed to update " + keyName + " of " + tableName, e);
        } finally {
            edao.stopTransaction(transaction);
        }
    }

    public void setAttribute(String ownerType, Long ownerId, String attrname, String attrvalue)
    throws DataAccessException {
        TransactionWrapper transaction = null;
        EngineDataAccessDB edao = new EngineDataAccessDB();
        try {
            transaction = edao.startTransaction();
            edao.setAttribute(ownerType, ownerId, attrname, attrvalue);
        } catch (SQLException e) {
            throw new DataAccessException(-1, "Failed to set attribute for " + ownerType + ": " + ownerId, e);
        } finally {
            edao.stopTransaction(transaction);
        }
    }

    public void setAttributes(String ownerType, Long ownerId, Map<String,String> attributes)
    throws DataAccessException {
        TransactionWrapper transaction = null;
        EngineDataAccessDB edao = new EngineDataAccessDB();
        try {
            transaction = edao.startTransaction();
            edao.setAttributes(ownerType, ownerId, attributes);
        } catch (SQLException e) {
            throw new DataAccessException(-1, "Failed to set attributes for " + ownerType + ": " + ownerId, e);
        } finally {
            edao.stopTransaction(transaction);
        }
    }

    public Map<String,String> getAttributes(String ownerType, Long ownerId)
    throws DataAccessException {
        TransactionWrapper transaction = null;
        EngineDataAccessDB edao = new EngineDataAccessDB();
        try {
            transaction = edao.startTransaction();
            List<Attribute> attrs = edao.getAttributes(ownerType, ownerId);
            if (attrs == null)
                return null;
            Map<String,String> attributes = new HashMap<String,String>();
            for (Attribute attr : attrs)
                attributes.put(attr.getAttributeName(), attr.getAttributeValue());
            return attributes;
        } catch (SQLException e) {
            throw new DataAccessException(-1, "Failed to get attributes for " + ownerType + ": " + ownerId, e);
        } finally {
            edao.stopTransaction(transaction);
        }
    }

    private static List<ServiceHandler> serviceHandlers = new ArrayList<ServiceHandler>();

    public void registerServiceHandler(ServiceHandler handler) throws EventException {
        if (!serviceHandlers.contains(handler))
            serviceHandlers.add(handler);
    }

    public void unregisterServiceHandler(ServiceHandler handler) throws EventException {
        serviceHandlers.remove(handler);
    }

    public ServiceHandler getServiceHandler(String protocol, String path) throws EventException {
        for (ServiceHandler serviceHandler : serviceHandlers) {
            if (protocol.equals(serviceHandler.getProtocol())
                && ((path == null && serviceHandler.getPath() == null)
                     || (path != null && path.equals(serviceHandler.getPath()))) ) {
                return serviceHandler;
            }
        }
        return null;
    }

    private static List<WorkflowHandler> workflowHandlers = new ArrayList<WorkflowHandler>();

    public void registerWorkflowHandler(WorkflowHandler handler) throws EventException {
        if (!workflowHandlers.contains(handler))
            workflowHandlers.add(handler);
    }

    public void unregisterWorkflowHandler(WorkflowHandler handler) throws EventException {
        workflowHandlers.remove(handler);
    }

    public WorkflowHandler getWorkflowHandler(String asset, Map<String,String> parameters) throws EventException {
        for (WorkflowHandler workflowHandler : workflowHandlers) {
            if (asset.equals(workflowHandler.getAsset())) {
                if (parameters == null) {
                    if (workflowHandler.getParameters() == null)
                        return workflowHandler;
                    else
                        continue;
                }
                else if (workflowHandler.getParameters() == null) {
                    continue;
                }
                boolean match = true;
                for (String paramName : parameters.keySet()) {
                    if (!(parameters.get(paramName).equals(workflowHandler.getParameters().get(paramName)))) {
                        match = false;
                        break;
                    }
                }
                if (match)
                    return workflowHandler;
            }
        }
        return null;
    }
    /**
     * Returns Document VO
     * @param forUpdate
     * @throws com.centurylink.mdw.dataaccess.DataAccessException
     */
    public Document getDocument(DocumentReference docRef, boolean forUpdate)
    throws ProcessException, com.centurylink.mdw.dataaccess.DataAccessException {
        TransactionWrapper transaction = null;
        EngineDataAccessDB edao = new EngineDataAccessDB();
        try {
            transaction = edao.startTransaction();
            Document docvo = edao.getDocument(docRef.getDocumentId(), forUpdate);
            return docvo;
        } catch (SQLException e) {
            throw new DataAccessException(-1, "Failed to get document content", e);
        } finally {
            edao.stopTransaction(transaction);
        }
    }

    @Override
    public Process findProcessByProcessInstanceId(Long processInstanceId) throws DataAccessException,
            ProcessException {
        ProcessInstance processInst = getProcessInstance(processInstanceId);
        if (processInst.isEmbedded()) {
            processInst = getProcessInstance(processInst.getOwnerId());
        }
        Process process = processInst != null ? ProcessCache.getProcess(processInst.getProcessId()) : null;
        return process;
    }

}
