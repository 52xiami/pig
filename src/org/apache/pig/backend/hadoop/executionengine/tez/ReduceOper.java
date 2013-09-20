/**
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
package org.apache.pig.backend.hadoop.executionengine.tez;

import java.util.Map;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.yarn.api.records.LocalResource;
import org.apache.pig.impl.plan.OperatorKey;
import org.apache.tez.dag.api.Vertex;
import org.apache.tez.mapreduce.processor.reduce.ReduceProcessor;

public class ReduceOper extends TezOperator {

    private static final long serialVersionUID = 1L;

    public ReduceOper(OperatorKey k) {
        super(k);
        // TODO Auto-generated constructor stub
    }

    @Override
    public String getProcessorName() {
        return ReduceProcessor.class.getName();
    }

    @Override
    public void configureVertex(Vertex operVertex, Configuration operConf,
            Map<String, LocalResource> commonLocalResources,
            Path remoteStagingDir) {
        // TODO Auto-generated method stub
    }
}

