package io.jenkins.blueocean.rest.impl.pipeline;

import com.google.common.base.Predicate;
import hudson.model.Action;
import hudson.model.Queue;
import hudson.model.Run;
import io.jenkins.blueocean.rest.model.BlueRun;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.pipeline.StageStatus;
import org.jenkinsci.plugins.pipeline.SyntheticStage;
import org.jenkinsci.plugins.workflow.actions.ErrorAction;
import org.jenkinsci.plugins.workflow.actions.LabelAction;
import org.jenkinsci.plugins.workflow.actions.LogAction;
import org.jenkinsci.plugins.workflow.actions.StageAction;
import org.jenkinsci.plugins.workflow.actions.TagsAction;
import org.jenkinsci.plugins.workflow.actions.ThreadNameAction;
import org.jenkinsci.plugins.workflow.cps.nodes.StepAtomNode;
import org.jenkinsci.plugins.workflow.cps.nodes.StepEndNode;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException;
import org.jenkinsci.plugins.workflow.support.actions.PauseAction;
import org.jenkinsci.plugins.workflow.support.steps.ExecutorStepExecution;
import org.jenkinsci.plugins.workflow.support.steps.input.InputAction;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.util.List;

/**
 * @author Vivek Pandey
 */
public class PipelineNodeUtil {

    @Nonnull
    public static BlueRun.BlueRunResult getStatus(@Nullable ErrorAction errorAction){
        if(errorAction == null){
            return BlueRun.BlueRunResult.SUCCESS;
        }else{
            return getStatus(errorAction.getError());
        }
    }

    @Nonnull
    public static BlueRun.BlueRunResult getStatus(@Nonnull Throwable error){
        if(error instanceof FlowInterruptedException){
            return BlueRun.BlueRunResult.ABORTED;
        }else{
            return BlueRun.BlueRunResult.FAILURE;
        }
    }

    @Nonnull
    public static NodeRunStatus getStatus(@Nonnull WorkflowRun run){
        FlowExecution execution = run.getExecution();
        BlueRun.BlueRunResult result;
        BlueRun.BlueRunState state;
        if (execution == null) {
            result = BlueRun.BlueRunResult.NOT_BUILT;
            state = BlueRun.BlueRunState.QUEUED;
        } else if (execution.getCauseOfFailure() != null) {
            result = getStatus(execution.getCauseOfFailure());
            state = BlueRun.BlueRunState.FINISHED;
        } else if (execution.isComplete()) {
            result = BlueRun.BlueRunResult.SUCCESS;
            state = BlueRun.BlueRunState.FINISHED;
        } else {
            result = BlueRun.BlueRunResult.UNKNOWN;
            state = BlueRun.BlueRunState.RUNNING;
        }
        return new NodeRunStatus(result,state);
    }


    @Nonnull
    public static String getDisplayName(@Nonnull FlowNode node) {
        return node.getAction(ThreadNameAction.class) != null
            ? node.getAction(ThreadNameAction.class).getThreadName()
            : node.getDisplayName();
    }

    public static boolean isStage(FlowNode node){
        return node !=null && ((node.getAction(StageAction.class) != null && !isSyntheticStage(node))
            || (node.getAction(LabelAction.class) != null && node.getAction(ThreadNameAction.class) == null));

    }

    public static boolean isSyntheticStage(@Nullable FlowNode node){
        return node!= null && getSyntheticStage(node) != null;
    }

    @CheckForNull
    public static TagsAction getSyntheticStage(@Nullable FlowNode node){
        if(node != null) {
            for (Action action : node.getActions()) {
                if (action instanceof TagsAction && ((TagsAction) action).getTagValue(SyntheticStage.TAG_NAME) != null) {
                    return (TagsAction) action;
                }
            }
        }
        return null;
    }

    public static boolean isPostSyntheticStage(@Nullable FlowNode node){
        if(node == null){
            return false;
        }
        TagsAction tagsAction = getSyntheticStage(node);
        if(tagsAction == null){
            return false;
        }
        String value = tagsAction.getTagValue(SyntheticStage.TAG_NAME);
        return value!=null && value.equals(SyntheticStage.getPost());
    }

    public static boolean isSkippedStage(@Nullable FlowNode node){
        if(node == null){
            return false;
        }
        for (Action action : node.getActions()) {
            if (action instanceof TagsAction && ((TagsAction) action).getTagValue(StageStatus.TAG_NAME) != null) {
                TagsAction tagsAction =  (TagsAction) action;
                String value = tagsAction.getTagValue(StageStatus.TAG_NAME);
                return value != null && value.equals(StageStatus.getSkippedForConditional());
            }
        }
        return false;
    }

    public static boolean isPreSyntheticStage(@Nullable FlowNode node){
        if(node == null){
            return false;
        }
        TagsAction tagsAction = getSyntheticStage(node);
        if(tagsAction == null){
            return false;
        }
        String value = tagsAction.getTagValue(SyntheticStage.TAG_NAME);
        return value!=null && value.equals(SyntheticStage.getPre());
    }

    public static boolean isParallelBranch(@Nullable FlowNode node){
        return node !=null && node.getAction(LabelAction.class) != null &&
            node.getAction(ThreadNameAction.class) != null;
    }

    /**
     *  Gives cause of block for declarative style plugin where agent (node block) is declared inside a stage.
     *  <pre>
     *    pipeline {
     *      agent none
     *      stages {
     *          stage ('first') {
     *              agent {
     *                  label 'first'
     *              }
     *              steps{
     *                  sh 'echo "from first"'
     *              }
     *          }
     *      }
     *    }
     *  </pre>
     *
     * @param stage stage's {@link FlowNode}
     * @param nodeBlock agent or node block's {@link FlowNode}
     * @param run {@link WorkflowRun} instance
     * @return cause of block if present, nul otherwise
     * @throws IOException in case of IOException
     * @throws InterruptedException in case of Interrupted exception
     */
    public static @CheckForNull String getCauseOfBlockage(@Nonnull FlowNode stage, @Nullable FlowNode nodeBlock, @Nonnull WorkflowRun run) throws IOException, InterruptedException {
        if(nodeBlock != null){
            //Check and see if this node block is inside this stage
            for(FlowNode p:nodeBlock.getParents()){
                if(p.equals(stage)){

                    //see if there is blocked item in queue
                    for(Queue.Item i: Jenkins.getInstance().getQueue().getItems()){
                        if(i.task instanceof ExecutorStepExecution.PlaceholderTask){
                            ExecutorStepExecution.PlaceholderTask task = (ExecutorStepExecution.PlaceholderTask) i.task;
                            String cause = i.getCauseOfBlockage().getShortDescription();
                            if(task.getCauseOfBlockage() != null){
                                cause = task.getCauseOfBlockage().getShortDescription();
                            }

                            Run r = task.runForDisplay();

                            //Set cause if its there and run and node block in the queue is same as the one we
                            if(cause != null && r != null && r.equals(run) && task.getNode() != null && task.getNode().equals(nodeBlock)){
                                return cause;
                            }

                        }
                    }
                }
            }
        }
        return null;
    }

    public static Predicate<FlowNode> isLoggable = new Predicate<FlowNode>() {
        @Override
        public boolean apply(@Nullable FlowNode input) {
            if(input == null)
                return false;
            return input.getAction(LogAction.class) != null;
        }
    };


    static boolean isNestedInParallel(@Nonnull List<FlowNode> sortedNodes, @Nonnull FlowNode node){
        FlowNode p = getClosestEnclosingParallelBranch(sortedNodes,node, node.getParents());
        return isInBlock(p, getStepEndNode(sortedNodes, p), node);
    }



    private static FlowNode getClosestEnclosingParallelBranch(List<FlowNode> sortedNodes, FlowNode node, List<FlowNode> parents){
        for(FlowNode n: parents){
            if(isParallelBranch(n)){
                if(isInBlock(n, getStepEndNode(sortedNodes, n), node)) {
                    return n;
                }
            }
            return getClosestEnclosingParallelBranch(sortedNodes, node, n.getParents());
        }
        return null;
    }

    public static boolean isPausedForInputStep(@Nonnull StepAtomNode step, @Nonnull WorkflowRun run){
        return isPausedForInputStep(step, run.getAction(InputAction.class));
    }

    public static boolean isPausedForInputStep(@Nonnull StepAtomNode step, @Nullable InputAction inputAction){
        if(inputAction == null){
            return false;
        }
        PauseAction pauseAction = step.getAction(PauseAction.class);
        return (pauseAction != null && pauseAction.isPaused()
                && pauseAction.getCause().equals("Input"));
    }

    static FlowNode getStepEndNode(List<FlowNode> sortedNodes, FlowNode startNode){
        for(int i = sortedNodes.size() - 1; i >=0; i--){
            FlowNode n = sortedNodes.get(i);
            if(n instanceof StepEndNode){
                StepEndNode endNode = (StepEndNode) n;
                if(endNode.getStartNode().equals(startNode))
                    return endNode;
            }
        }
        return null;
    }

    static FlowNode getEndNode(List<FlowNode> sortedNodes, FlowNode startNode){
        for(int i = sortedNodes.size() - 1; i >=0; i--){
            FlowNode n = sortedNodes.get(i);
            if(n instanceof StepAtomNode){
                StepAtomNode endNode = (StepAtomNode) n;
                if(endNode.equals(startNode))
                    return endNode;
            }
        }
        return null;
    }

    static boolean isInBlock(FlowNode startNode, FlowNode endNode, FlowNode c){
        return isChildOf(startNode, c) && isChildOf(c, endNode);
    }

    private static boolean isChildOf(FlowNode parent, FlowNode child){
        if(child == null){
            return false;
        }
        for(FlowNode p:child.getParents()){
            if(p.equals(parent)){
                return true;
            }
            return isChildOf(parent, p);
        }
        return false;
    }

    private static boolean isBranchNestedInBranch(FlowNode node){
        for(FlowNode n: node.getParents()){
            if(isParallelBranch(node)){
                return true;
            }
            isBranchNestedInBranch(n);
        }
        return false;
    }

}
