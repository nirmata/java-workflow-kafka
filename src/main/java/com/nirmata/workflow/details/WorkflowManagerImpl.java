package com.nirmata.workflow.details;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.nirmata.workflow.WorkflowManager;
import com.nirmata.workflow.admin.RunInfo;
import com.nirmata.workflow.admin.WorkflowAdmin;
import com.nirmata.workflow.details.internalmodels.RunnableTask;
import com.nirmata.workflow.executor.TaskExecution;
import com.nirmata.workflow.executor.TaskExecutor;
import com.nirmata.workflow.models.ExecutableTask;
import com.nirmata.workflow.models.RunId;
import com.nirmata.workflow.models.Task;
import com.nirmata.workflow.models.TaskExecutionResult;
import com.nirmata.workflow.models.TaskId;
import com.nirmata.workflow.models.TaskType;
import com.nirmata.workflow.queue.QueueConsumer;
import com.nirmata.workflow.queue.QueueFactory;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.utils.CloseableUtils;
import org.apache.curator.utils.ZKPaths;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.data.Stat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class WorkflowManagerImpl implements WorkflowManager, WorkflowAdmin
{
    private final Logger log = LoggerFactory.getLogger(getClass());
    private final CuratorFramework curator;
    private final String instanceName;
    private final List<QueueConsumer> consumers;
    private final SchedulerSelector schedulerSelector;
    private final AtomicReference<State> state = new AtomicReference<>(State.LATENT);

    private static final TaskType nullTaskType = new TaskType("", "", false);

    private enum State
    {
        LATENT,
        STARTED,
        CLOSED
    }

    public WorkflowManagerImpl(CuratorFramework curator, QueueFactory queueFactory, String instanceName, List<TaskExecutorSpec> specs)
    {
        this.curator = Preconditions.checkNotNull(curator, "curator cannot be null");
        queueFactory = Preconditions.checkNotNull(queueFactory, "queueFactory cannot be null");
        this.instanceName = Preconditions.checkNotNull(instanceName, "instanceName cannot be null");
        specs = Preconditions.checkNotNull(specs, "specs cannot be null");

        consumers = makeTaskConsumers(queueFactory, specs);
        schedulerSelector = new SchedulerSelector(this, queueFactory, specs);
    }

    public CuratorFramework getCurator()
    {
        return curator;
    }

    @Override
    public void start()
    {
        Preconditions.checkState(state.compareAndSet(State.LATENT, State.STARTED), "Already started");

        consumers.forEach(QueueConsumer::start);
        schedulerSelector.start();
    }

    @Override
    public RunId submitTask(Task task)
    {
        return submitSubTask(null, task);
    }

    @Override
    public RunId submitSubTask(RunId parentRunId, Task task)
    {
        Preconditions.checkState(state.get() == State.STARTED, "Not started");

        RunId runId = new RunId();
        RunnableTaskDagBuilder builder = new RunnableTaskDagBuilder(task);
        Map<TaskId, ExecutableTask> tasks = builder
            .getTasks()
            .values()
            .stream()
            .collect(Collectors.toMap(Task::getTaskId, t -> new ExecutableTask(runId, t.getTaskId(), t.isExecutable() ? t.getTaskType() : nullTaskType, t.getMetaData(), t.isExecutable())));
        RunnableTask runnableTask = new RunnableTask(tasks, builder.getEntries(), LocalDateTime.now(), null, parentRunId);

        try
        {
            byte[] runnableTaskJson = JsonSerializer.toBytes(JsonSerializer.newRunnableTask(runnableTask));
            String runPath = ZooKeeperConstants.getRunPath(runId);
            curator.create().creatingParentsIfNeeded().forPath(runPath, runnableTaskJson);
        }
        catch ( Exception e )
        {
            throw new RuntimeException(e);
        }

        return runId;
    }

    @Override
    public boolean cancelRun(RunId runId)
    {
        log.info("Attempting to cancel run " + runId);

        String runPath = ZooKeeperConstants.getRunPath(runId);
        try
        {
            Stat stat = new Stat();
            byte[] json = curator.getData().storingStatIn(stat).forPath(runPath);
            RunnableTask runnableTask = JsonSerializer.getRunnableTask(JsonSerializer.fromBytes(json));
            Scheduler.completeRunnableTask(log, this, runId, runnableTask, stat.getVersion());
            return true;
        }
        catch ( KeeperException.NoNodeException ignore )
        {
            return false;
        }
        catch ( Exception e )
        {
            throw new RuntimeException("Could not cancel runId " + runId, e);
        }
    }

    @Override
    public Optional<Map<String, String>> getTaskData(RunId runId, TaskId taskId)
    {
        String completedTaskPath = ZooKeeperConstants.getCompletedTaskPath(runId, taskId);
        try
        {
            byte[] json = curator.getData().forPath(completedTaskPath);
            TaskExecutionResult taskExecutionResult = JsonSerializer.getTaskExecutionResult(JsonSerializer.fromBytes(json));
            return Optional.of(taskExecutionResult.getResultData());
        }
        catch ( KeeperException.NoNodeException dummy )
        {
            // dummy
        }
        catch ( Exception e )
        {
            throw new RuntimeException(String.format("No data for runId %s taskId %s", runId, taskId), e);
        }
        return Optional.empty();
    }

    public String getInstanceName()
    {
        return instanceName;
    }

    @Override
    public void close() throws IOException
    {
        if ( state.compareAndSet(State.STARTED, State.CLOSED) )
        {
            CloseableUtils.closeQuietly(schedulerSelector);
            consumers.forEach(CloseableUtils::closeQuietly);
        }
    }

    @Override
    public WorkflowAdmin getAdmin()
    {
        return this;
    }

    @Override
    public List<RunInfo> getRunInfo()
    {
        try
        {
            String runParentPath = ZooKeeperConstants.getRunParentPath();
            return curator.getChildren().forPath(runParentPath).stream()
                .map(child -> {
                    String fullPath = ZKPaths.makePath(runParentPath, child);
                    try
                    {
                        RunId runId = new RunId(ZooKeeperConstants.getRunIdFromRunPath(fullPath));
                        byte[] json = curator.getData().forPath(fullPath);
                        RunnableTask runnableTask = JsonSerializer.getRunnableTask(JsonSerializer.fromBytes(json));
                        return new RunInfo(runId, runnableTask.getStartTime(), runnableTask.getCompletionTime().orElse(null));
                    }
                    catch ( KeeperException.NoNodeException ignore )
                    {
                        // ignore - must have been deleted in the interim
                    }
                    catch ( Exception e )
                    {
                        String message = "Trying to read run info from: " + fullPath;
                        log.error(message, e);
                        throw new RuntimeException(message, e);
                    }
                    return null;
                })
                .filter(info -> (info != null))
                .collect(Collectors.toList());
        }
        catch ( Exception e )
        {
            throw new RuntimeException(e);
        }
    }

    private void executeTask(TaskExecutor taskExecutor, ExecutableTask executableTask)
    {
        if ( state.get() != State.STARTED )
        {
            return;
        }

        log.info("Executing task: " + executableTask);
        TaskExecution taskExecution = taskExecutor.newTaskExecution(this, executableTask);

        TaskExecutionResult result = taskExecution.execute();
        String json = JsonSerializer.nodeToString(JsonSerializer.newTaskExecutionResult(result));
        try
        {
            String path = ZooKeeperConstants.getCompletedTaskPath(executableTask.getRunId(), executableTask.getTaskId());
            curator.create().creatingParentsIfNeeded().forPath(path, json.getBytes());
        }
        catch ( Exception e )
        {
            log.error("Could not set completed data for executable task: " + executableTask, e);
            throw new RuntimeException(e);
        }
    }

    private List<QueueConsumer> makeTaskConsumers(QueueFactory queueFactory, List<TaskExecutorSpec> specs)
    {
        ImmutableList.Builder<QueueConsumer> builder = ImmutableList.builder();
        specs.forEach(spec -> IntStream.range(0, spec.getQty()).forEach(i -> {
            QueueConsumer consumer = queueFactory.createQueueConsumer(this, t -> executeTask(spec.getTaskExecutor(), t), spec.getTaskType());
            builder.add(consumer);
        }));

        return builder.build();
    }
}
