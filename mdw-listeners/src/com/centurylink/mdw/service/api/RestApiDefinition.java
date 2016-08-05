/**
 * Copyright (c) 2016 CenturyLink, Inc. All Rights Reserved.
 */
package com.centurylink.mdw.service.api;

import io.swagger.annotations.ExternalDocs;
import io.swagger.annotations.Info;
import io.swagger.annotations.SwaggerDefinition;

/**
 * Marker interface for top-level swagger definition.
 */
@SwaggerDefinition(
  info=@Info(title="MDW REST API", description="MDW Application Services", version="5.5.36"),
  schemes={SwaggerDefinition.Scheme.HTTP, SwaggerDefinition.Scheme.HTTPS},
  basePath="/mdw/services",
  externalDocs=@ExternalDocs(value="MDW", url="http://cshare.ad.qintra.com/sites/mdw/services"))
public interface RestApiDefinition {

}
