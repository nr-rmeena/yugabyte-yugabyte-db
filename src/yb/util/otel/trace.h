// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
//
// The following only applies to changes made to this file as part of YugaByte development.
//
// Portions Copyright (c) YugaByte, Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
// in compliance with the License.  You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software distributed under the License
// is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
// or implied.  See the License for the specific language governing permissions and limitations
// under the License.
//

#pragma once

#include <stddef.h>
#include <string>

#include "opentelemetry/nostd/shared_ptr.h"
#include "opentelemetry/trace/provider.h"
#include "opentelemetry/trace/span.h"

namespace trace_api      = opentelemetry::trace;
namespace nostd          = opentelemetry::nostd;

namespace yb {

static const size_t kTraceIdSize             = 32;
static const size_t kSpanIdSize              = 16;

void InitPgTracer(int pid);
void InitTserverTracer(const std::string& host_name, const std::string& uuid);

void CleanupTracer();

nostd::shared_ptr<trace_api::Tracer> get_tracer(std::string tracer_name);
nostd::shared_ptr<trace_api::Span> CreateSpanFromParentId(
    const std::string& trace_id, const std::string& span_id, const std::string& span);
nostd::shared_ptr<trace_api::Span> CreateSpan(const std::string& span_name);

} //namespace