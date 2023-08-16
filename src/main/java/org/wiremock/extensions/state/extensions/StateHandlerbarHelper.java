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
package org.wiremock.extensions.state.extensions;

import com.github.jknack.handlebars.Options;
import com.github.tomakehurst.wiremock.common.Json;
import com.github.tomakehurst.wiremock.common.JsonException;
import com.github.tomakehurst.wiremock.extension.responsetemplating.helpers.HandlebarsHelper;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import org.apache.commons.lang3.StringUtils;
import org.wiremock.extensions.state.internal.Context;
import org.wiremock.extensions.state.internal.ContextManager;

import java.util.ArrayList;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

import static com.github.tomakehurst.wiremock.common.LocalNotifier.notifier;

/**
 * Response templating helper to access state.
 * <p>
 * DO NOT REGISTER directly. Use {@link org.wiremock.extensions.state.StateExtension} instead.
 *
 * @see org.wiremock.extensions.state.StateExtension
 */
public class StateHandlerbarHelper extends HandlebarsHelper<Object> {

    private final ContextManager contextManager;

    public StateHandlerbarHelper(ContextManager contextManager) {
        this.contextManager = contextManager;
    }

    @Override
    public Object apply(Object o, Options options) {
        String contextName = options.hash("context");
        String property = options.hash("property");
        String list = options.hash("list");
        String defaultValue = options.hash("default");
        if (StringUtils.isEmpty(contextName)) {
            return handleError("'context' cannot be empty");
        }
        if (StringUtils.isBlank(property) && StringUtils.isBlank(list)) {
            return handleError("Either 'property' or 'list' has to be set");
        }
        if (StringUtils.isNotBlank(property) && StringUtils.isNotBlank(list)) {
            return handleError("Either 'property' or 'list' has to be set");
        }
        if (StringUtils.isNotBlank(property)) {
            return getProperty(contextName, property, defaultValue)
                .orElseGet(() -> handleError(String.format("No state for context %s, property %s found", contextName, property)));
        } else {
            return getList(contextName, list)
                .orElseGet(() ->
                    Optional.ofNullable(defaultValue)
                        .orElseGet(() -> handleError(String.format("No state for context %s, list %s found", contextName, list)))
                );

        }
    }

    private Optional<Object> getProperty(String contextName, String property, String defaultValue) {
        return contextManager.getContext(contextName)
            .map(context ->
                Stream.of(SpecialProperties.values())
                    .filter(it -> it.name().equals(property))
                    .findFirst()
                    .map(it -> it.getFromContext(context))
                    .orElseGet(() -> context.getProperties().get(property))
            )
            .or(() -> convertToPropertySpecificDefault(property, defaultValue));
    }

    private Optional<Object> convertToPropertySpecificDefault(String property, String defaultValue) {
        return Stream.of(SpecialProperties.values())
            .filter(it -> it.name().equals(property))
            .findFirst()
            .map(it -> it.convertDefaultValue(defaultValue))
            .or(() -> Optional.ofNullable(defaultValue));
    }

    private Optional<Object> getList(String contextName, String list) {
        return contextManager.getContext(contextName)
            .flatMap(context -> {
                try {
                    return Optional.of(JsonPath.read(context.getList(), list));
                } catch (PathNotFoundException e) {
                    notifier().info("Path query failed: " + e.getMessage());
                    return Optional.empty();
                }
            });
    }

    private enum SpecialProperties {
        updateCount(Context::getUpdateCount, it -> it),
        listSize((context) -> context.getList().size(), it -> it),
        @SuppressWarnings("rawtypes") list(
            Context::getList,
            (defaultValue) -> Optional.ofNullable(defaultValue)
                .map(it -> {
                    try {
                        return Json.read(it, ArrayList.class);
                    } catch (JsonException ex) {
                        notifier().error("default for list property is not a JSON list - fallback to empty list: " + defaultValue);
                        return null;
                    }
                })
                .or(() -> Optional.of(new ArrayList()))
                .map(it -> (Object) it)
                .get()
        );

        private final Function<Context, Object> contextExtractor;
        private final Function<String, Object> defaultConverter;

        SpecialProperties(Function<Context, Object> contextExtractor, Function<String, Object> defaultConverter) {
            this.contextExtractor = contextExtractor;
            this.defaultConverter = defaultConverter;
        }

        public Object getFromContext(Context context) {
            return contextExtractor.apply(context);
        }

        public Object convertDefaultValue(String defaultValue) {
            return defaultConverter.apply(defaultValue);
        }
    }

}
