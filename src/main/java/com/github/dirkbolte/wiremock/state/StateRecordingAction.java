/*
 * Copyright (C) 2023 Dirk Bolte
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.dirkbolte.wiremock.state;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.tomakehurst.wiremock.core.Admin;
import com.github.tomakehurst.wiremock.core.ConfigurationException;
import com.github.tomakehurst.wiremock.extension.Parameters;
import com.github.tomakehurst.wiremock.extension.PostServeAction;
import com.github.tomakehurst.wiremock.extension.responsetemplating.RequestTemplateModel;
import com.github.tomakehurst.wiremock.extension.responsetemplating.TemplateEngine;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import org.apache.commons.lang3.StringUtils;

import javax.swing.text.html.Option;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;

public class StateRecordingAction extends PostServeAction {

    private static final int DEFAULT_EXPIRATION_SECONDS = 60 * 60;
    private final TemplateEngine templateEngine;

    private final Cache<String, Object> cache;

    public StateRecordingAction() {
        this(0);
    }

    @JsonCreator
    public StateRecordingAction(int expirationSeconds) {
        this.templateEngine = new TemplateEngine(Collections.emptyMap(), null, Collections.emptySet());

        var builder = Caffeine.newBuilder();
        if (expirationSeconds == 0) {
            builder.expireAfterWrite(Duration.ofSeconds(DEFAULT_EXPIRATION_SECONDS));
        } else {
            builder.expireAfterWrite(Duration.ofSeconds(expirationSeconds));
        }
        cache = builder.build();
    }

    @Override
    public void doAction(ServeEvent serveEvent, Admin admin, Parameters parameters) {
        var model = Map.of(
            "request", RequestTemplateModel.from(serveEvent.getRequest()),
            "response", ResponseTemplateModel.from(serveEvent.getResponse())
        );
        var context = createContext(model, parameters);
        storeContextAndState(context, model, parameters);
    }

    @Override
    public String getName() {
        return "recordState";
    }

    Object getState(String context, String property) {
        return cache.getIfPresent(calculateKey(context, property));
    }

    boolean hasContext(String context) {
        return cache.getIfPresent(context) != null;
    }

    private void storeContextAndState(String context, Map<String, Object> model, Parameters parameters) {
        @SuppressWarnings("unchecked") Map<String, Object> state = Optional.ofNullable(parameters.get("state"))
            .filter(it -> it instanceof Map)
            .map(Map.class::cast)
            .orElseThrow(() -> new ConfigurationException("no state specified"));
        cache.put(context, context);
        state.entrySet()
            .stream()
            .map(entry -> Map.entry(entry.getKey(), renderTemplate(model, entry.getValue().toString())))
            .forEach(entry -> storeState(context, entry.getKey(), entry.getValue()));
    }

    private String createContext(Map<String, Object> model, Parameters parameters) {
        var rawContext = Optional.ofNullable(parameters.getString("context")).filter(StringUtils::isNotBlank).orElseThrow(() -> new ConfigurationException("no context specified"));
        String context = renderTemplate(model, rawContext);
        if (StringUtils.isBlank(context)) {
            throw new ConfigurationException("context is blank");
        }
        return context;
    }

    String renderTemplate(Object context, String value) {
        return templateEngine.getUncachedTemplate(value).apply(context);
    }

    private void storeState(String context, String property, Object value) {
        cache.put(calculateKey(context, property), value);
    }

    private String calculateKey(String context, String property) {
        return String.format("%s,%s", context, property);
    }
}
