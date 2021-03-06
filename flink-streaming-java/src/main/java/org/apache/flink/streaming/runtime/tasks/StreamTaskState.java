/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.streaming.runtime.tasks;

import org.apache.flink.annotation.Internal;
import org.apache.flink.runtime.state.StateHandle;
import org.apache.flink.runtime.state.KvStateSnapshot;
import org.apache.flink.util.ExceptionUtils;

import java.io.Closeable;
import java.io.IOException;
import java.io.Serializable;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.Iterator;

/**
 * The state checkpointed by a {@link org.apache.flink.streaming.api.operators.AbstractStreamOperator}.
 * This state consists of any combination of those three:
 * <ul>
 *     <li>The state of the stream operator, if it implements the Checkpointed interface.</li>
 *     <li>The state of the user function, if it implements the Checkpointed interface.</li>
 *     <li>The key/value state of the operator, if it executes on a KeyedDataStream.</li>
 * </ul>
 */
@Internal
public class StreamTaskState implements Serializable, Closeable {

	private static final long serialVersionUID = 1L;
	
	private StateHandle<?> operatorState;

	private StateHandle<Serializable> functionState;

	private HashMap<String, KvStateSnapshot<?, ?, ?, ?, ?>> kvStates;

	// ------------------------------------------------------------------------

	public StateHandle<?> getOperatorState() {
		return operatorState;
	}

	public void setOperatorState(StateHandle<?> operatorState) {
		this.operatorState = operatorState;
	}

	public StateHandle<Serializable> getFunctionState() {
		return functionState;
	}

	public void setFunctionState(StateHandle<Serializable> functionState) {
		this.functionState = functionState;
	}

	public HashMap<String, KvStateSnapshot<?, ?, ?, ?, ?>> getKvStates() {
		return kvStates;
	}

	public void setKvStates(HashMap<String, KvStateSnapshot<?, ?, ?, ?, ?>> kvStates) {
		this.kvStates = kvStates;
	}

	// ------------------------------------------------------------------------

	/**
	 * Checks if this state object actually contains any state, or if all of the state
	 * fields are null.
	 * 
	 * @return True, if all state is null, false if at least one state is not null.
	 */
	public boolean isEmpty() {
		return operatorState == null & functionState == null & kvStates == null;
	}

	/**
	 * Discards all the contained states and sets them to null.
	 * 
	 * @throws Exception Forwards exceptions that occur when releasing the
	 *                   state handles and snapshots.
	 */
	public void discardState() throws Exception {
		StateHandle<?> operatorState = this.operatorState;
		StateHandle<?> functionState = this.functionState;
		HashMap<String, KvStateSnapshot<?, ?, ?, ?, ?>> kvStates = this.kvStates;

		this.operatorState = null;
		this.functionState = null;
		this.kvStates = null;
	
		if (operatorState != null) {
			operatorState.discardState();
		}
		if (functionState != null) {
			functionState.discardState();
		}
		if (kvStates != null) {
			while (kvStates.size() > 0) {
				try {
					Iterator<KvStateSnapshot<?, ?, ?, ?, ?>> values = kvStates.values().iterator();
					while (values.hasNext()) {
						KvStateSnapshot<?, ?, ?, ?, ?> s = values.next();
						s.discardState();
						values.remove();
					}
				}
				catch (ConcurrentModificationException e) {
					// fall through the loop
				}
			}
		}
	}

	@Override
	public void close() throws IOException {
		StateHandle<?> operatorState = this.operatorState;
		StateHandle<?> functionState = this.functionState;
		HashMap<String, KvStateSnapshot<?, ?, ?, ?, ?>> kvStates = this.kvStates;

		this.operatorState = null;
		this.functionState = null;
		this.kvStates = null;

		Throwable firstException = null;

		if (operatorState != null) {
			try {
				operatorState.close();
			} catch (Throwable t) {
				firstException = t;
			}
		}

		if (functionState != null) {
			try {
				functionState.close();
			} catch (Throwable t) {
				if (firstException == null) {
					firstException = t;
				}
			}
		}
	
		if (kvStates != null) {
			while (kvStates.size() > 0) {
				try {
					Iterator<KvStateSnapshot<?, ?, ?, ?, ?>> values = kvStates.values().iterator();
					while (values.hasNext()) {
						KvStateSnapshot<?, ?, ?, ?, ?> s = values.next();
						try {
							s.close();
						} catch (Throwable t) {
							if (firstException == null) {
								firstException = t;
							}
						}
						values.remove();
					}
				}
				catch (ConcurrentModificationException e) {
					// fall through the loop
				}
			}
		}

		if (firstException != null) {
			ExceptionUtils.rethrowIOException(firstException);
		}
	}
}
