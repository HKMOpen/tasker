package com.mastertechsoftware.tasker;

import com.mastertechsoftware.logging.Logger;

import android.os.Handler;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingDeque;

/**
 * Class for Sequentially running tasks in the background
 */
public class Tasker {
	public enum THREAD_TYPE {
		UI,
		BACKGROUND
	}
	protected Handler handler = new Handler(); // This assumes the tasker class is created in the main thread
	protected final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
	protected LinkedBlockingDeque<Task> tasks = new LinkedBlockingDeque<>();
	protected TaskFinisher finisher;
	protected boolean noErrors = true;
	protected Task lastAddedTask;
	protected Map<ThreadRunnable, Future> runableMap = new ConcurrentHashMap<>();

	/**
	 * Simple create task to get us started
	 * @return Tasker
	 */
	static public Tasker create() {
		return new Tasker();
	}

	/**
	 * Add a new task
	 * @param task
	 * @return Tasker
	 */
	public Tasker addTask(Task task) {
		tasks.add(task);
		lastAddedTask = task;
		return this;
	}

	/**
	 * Add a new UI task
	 * @param task
	 * @return Tasker
	 */
	public Tasker addUITask(Task task) {
		tasks.add(task);
		task.setRunType(THREAD_TYPE.UI);
		lastAddedTask = task;
		return this;
	}

	/**
	 * Add a class that will handle the final ending moment
	 * @param finisher
	 * @return Tasker
	 */
	public Tasker addFinisher(TaskFinisher finisher) {
		this.finisher = finisher;
		return this;
	}

	public Tasker withCondition(Condition condition) {
		lastAddedTask.setCondition(condition); // Note: This will crash if you haven't added at least 1 task
		return this;
	}

	/**
	 * Cancel all running tasks
	 */
	public void cancelAll() {
		for (Future future : runableMap.values()) {
			future.cancel(true);
		}
		runableMap.clear();
		shutdown();
	}

	/**
	 * Shutdown the executor
	 */
	private void shutdown() {
		if (!mExecutor.isShutdown() && !mExecutor.isTerminated()) {
			mExecutor.shutdown();
		}
	}

	/**
	 * Cancel a single Handler
	 * @param task
	 * @return true if we found and cancelled the task
	 */
	public boolean cancelTaskHandler(Task task) {
		for (ThreadRunnable threadRunnable : runableMap.keySet()) {
			if (threadRunnable.task == task) {
				Future future = runableMap.remove(threadRunnable);
				if (future != null) {
					future.cancel(true);
				}
				return true;
			}
		}
		return false;
	}

	/**
	 * Start running all tasks
	 * @return true if everything started ok
	 */
	public boolean run() {
		// Wrap the whole thing so we can make sure to unlock in
		// case something throws.
		try {

			// If we're shutdown or terminated we can't accept any new requests.
			if (mExecutor.isShutdown() || mExecutor.isTerminated()) {
				Logger.error("Tasker:run - Executor is shutdown");
				return false;
			}

			for (Task handler : tasks) {
				final ThreadRunnable threadRunnable = new ThreadRunnable(handler);
				final Future future = mExecutor.submit(threadRunnable);
				runableMap.put(threadRunnable, future);
			}

			tasks.clear();

		} catch (Exception RejectedExecutionException) {
			return false;
		}
		return true;
	}

	/**
	 * Remove the runnable from our map and call the finisher if it's the last one
	 * @param threadRunnable
	 */
	protected void runnableFinished(ThreadRunnable threadRunnable) {
		runableMap.remove(threadRunnable);
		if (runableMap.isEmpty() && finisher != null) {
			shutdown();
			handler.post(new Runnable() {
				@Override
				public void run() {
					if (noErrors) {
						finisher.onSuccess();
					} else {
						finisher.onError();
					}
				}
			});
		}
	}


	/**
	 * Runnable that uses our callback and then runs the result on UI thread
	 * or background thread (which is what it is on)
	 */
	class ThreadRunnable implements Callable, Pausable {
		protected Task task;
		protected Object result;
		protected CountDownLatch uiWait = new CountDownLatch(1);
		protected CountDownLatch pauseWait = new CountDownLatch(1);
		protected boolean paused = false;

		ThreadRunnable(Task task) {
			this.task = task;
			task.setPauseable(this);
		}

		@Override
		public Object call() throws Exception {
			try {
				if (task.hasCondition() && !task.getCondition().shouldExecute()) {
					runnableFinished(this);
					return null;
				}

				if (task.runType() == THREAD_TYPE.BACKGROUND) {
					result = task.run();
				}
				// Make UI Thread calls
				handler.post(new Runnable() {
					@Override
					public void run() {
						try {
							if (task.runType() == THREAD_TYPE.UI) {
								result = task.run();
								task.setResult(result);
								uiWait.countDown();
							} else {
								task.setResult(result);
							}
						} catch (Exception e) {
							noErrors = false;
							task.setError(e);
							if (task.runType() == THREAD_TYPE.UI) {
								uiWait.countDown();
							}
						}
					}
				});
				// Wait until UI is finished
				if (task.runType() == THREAD_TYPE.UI) {
					uiWait.await();
				}
				if (paused) {
					pauseWait.await();
				}
				runnableFinished(this);
				if (!task.shouldContinue()) {
					cancelAll();
				}
				return result;
			} catch (final Exception e) {
				noErrors = false;
				handler.post(new Runnable() {
					@Override
					public void run() {
						task.setError(e);
					}
				});
			}
			runnableFinished(this);
			return null;
		}

		@Override
		public boolean isPaused() {
			return paused;
		}

		@Override
		public void setPaused(boolean paused) {
			this.paused = paused;
			if (!paused) {
				pauseWait.countDown();
			}
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;

			ThreadRunnable that = (ThreadRunnable) o;

			return task != null ? task.equals(that.task) : that.task == null;

		}

		@Override
		public int hashCode() {
			return task != null ? task.hashCode() : 0;
		}

	}
}
