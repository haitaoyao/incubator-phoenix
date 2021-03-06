/*
 * Copyright 2014 The Apache Software Foundation
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.phoenix.parse;

import java.sql.SQLException;



/**
 * 
 * Node representing a TABLE bound using an ARRAY variable
 * TODO: modify grammar to support this
 * 
 * @since 0.1
 */
public class BindTableNode extends ConcreteTableNode {
    
    public static BindTableNode create(String alias, TableName name, boolean isRewrite) {
        return new BindTableNode(alias, name, isRewrite);
    }

    BindTableNode(String alias, TableName name) {
        this(alias, name, false);
    }
    
    BindTableNode(String alias, TableName name, boolean isRewrite) {
        super(alias, name, isRewrite);
    }

    @Override
    public void accept(TableNodeVisitor visitor) throws SQLException {
        visitor.visit(this);
    }

}

