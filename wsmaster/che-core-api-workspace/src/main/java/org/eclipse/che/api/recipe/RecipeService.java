/*
 * Copyright (c) 2012-2017 Red Hat, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *   Red Hat, Inc. - initial API and implementation
 */
package org.eclipse.che.api.recipe;

import static com.google.common.base.Strings.isNullOrEmpty;
import static java.util.Arrays.asList;
import static javax.ws.rs.core.MediaType.APPLICATION_JSON;
import static javax.ws.rs.core.MediaType.TEXT_PLAIN;
import static javax.ws.rs.core.Response.Status.CREATED;
import static org.eclipse.che.api.workspace.shared.Constants.LINK_REL_CREATE_RECIPE;
import static org.eclipse.che.api.workspace.shared.Constants.LINK_REL_GET_RECIPE_SCRIPT;
import static org.eclipse.che.api.workspace.shared.Constants.LINK_REL_REMOVE_RECIPE;
import static org.eclipse.che.api.workspace.shared.Constants.LINK_REL_SEARCH_RECIPES;
import static org.eclipse.che.api.workspace.shared.Constants.LINK_REL_UPDATE_RECIPE;

import java.io.UnsupportedEncodingException;
import java.util.List;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriBuilder;
import org.eclipse.che.api.core.ApiException;
import org.eclipse.che.api.core.BadRequestException;
import org.eclipse.che.api.core.rest.Service;
import org.eclipse.che.api.core.rest.annotations.GenerateLink;
import org.eclipse.che.api.core.rest.shared.dto.Link;
import org.eclipse.che.api.core.util.LinksHelper;
import org.eclipse.che.api.workspace.shared.recipe.ManagedOldRecipe;
import org.eclipse.che.api.workspace.shared.recipe.NewOldRecipe;
import org.eclipse.che.api.workspace.shared.recipe.OldRecipeDescriptor;
import org.eclipse.che.api.workspace.shared.recipe.OldRecipeUpdate;
import org.eclipse.che.commons.env.EnvironmentContext;
import org.eclipse.che.commons.lang.NameGenerator;
import org.eclipse.che.dto.server.DtoFactory;

/**
 * OldRecipe API
 *
 * @author Eugene Voevodin
 */
@Path("/recipe")
public class RecipeService extends Service {

  private final RecipeDao recipeDao;

  @Inject
  public RecipeService(RecipeDao recipeDao) {
    this.recipeDao = recipeDao;
  }

  @POST
  @Consumes(APPLICATION_JSON)
  @Produces(APPLICATION_JSON)
  @GenerateLink(rel = LINK_REL_CREATE_RECIPE)
  public Response createRecipe(NewOldRecipe newRecipe) throws ApiException {
    if (newRecipe == null) {
      throw new BadRequestException("OldRecipe required");
    }
    if (isNullOrEmpty(newRecipe.getType())) {
      throw new BadRequestException("OldRecipe type required");
    }
    if (isNullOrEmpty(newRecipe.getScript())) {
      throw new BadRequestException("OldRecipe script required");
    }
    if (isNullOrEmpty(newRecipe.getName())) {
      throw new BadRequestException("OldRecipe name required");
    }
    String userId = EnvironmentContext.getCurrent().getSubject().getUserId();

    final OldRecipeImpl recipe =
        new OldRecipeImpl()
            .withId(NameGenerator.generate("recipe", 16))
            .withName(newRecipe.getName())
            .withCreator(userId)
            .withType(newRecipe.getType())
            .withScript(newRecipe.getScript())
            .withTags(newRecipe.getTags());
    recipeDao.create(recipe);

    return Response.status(CREATED).entity(asRecipeDescriptor(recipe)).build();
  }

  @GET
  @Path("/{id}/script")
  public Response getRecipeScript(@PathParam("id") String id)
      throws ApiException, UnsupportedEncodingException {
    // Do not remove!
    // Docker can not use dockerfile in some cases without content-length header.
    final ManagedOldRecipe recipe = recipeDao.getById(id);
    byte[] script = recipe.getScript().getBytes("UTF-8");
    return Response.ok(script, MediaType.TEXT_PLAIN)
        .header(HttpHeaders.CONTENT_LENGTH, script.length)
        .build();
  }

  @GET
  @Path("/{id}")
  @Produces(APPLICATION_JSON)
  public OldRecipeDescriptor getRecipe(@PathParam("id") String id) throws ApiException {
    return asRecipeDescriptor(recipeDao.getById(id));
  }

  @GET
  @Produces(APPLICATION_JSON)
  @GenerateLink(rel = LINK_REL_SEARCH_RECIPES)
  public List<OldRecipeDescriptor> searchRecipes(
      @QueryParam("tags") List<String> tags,
      @QueryParam("type") String type,
      @DefaultValue("0") @QueryParam("skipCount") Integer skipCount,
      @DefaultValue("30") @QueryParam("maxItems") Integer maxItems)
      throws ApiException {
    final String currentUser = EnvironmentContext.getCurrent().getSubject().getUserId();
    return recipeDao
        .search(currentUser, tags, type, skipCount, maxItems)
        .stream()
        .map(this::asRecipeDescriptor)
        .collect(Collectors.toList());
  }

  @PUT
  @Consumes(APPLICATION_JSON)
  @Produces(APPLICATION_JSON)
  @GenerateLink(rel = LINK_REL_UPDATE_RECIPE)
  public OldRecipeDescriptor updateRecipe(OldRecipeUpdate update) throws ApiException {
    if (update == null) {
      throw new BadRequestException("Update required");
    }
    if (update.getId() == null) {
      throw new BadRequestException("OldRecipe id required");
    }

    return asRecipeDescriptor(recipeDao.update(new OldRecipeImpl(update)));
  }

  @DELETE
  @Path("/{id}")
  public void removeRecipe(@PathParam("id") String id) throws ApiException {
    recipeDao.remove(id);
  }

  /** Transforms {@link ManagedOldRecipe} to {@link OldRecipeDescriptor}. */
  private OldRecipeDescriptor asRecipeDescriptor(ManagedOldRecipe recipe) {
    final OldRecipeDescriptor descriptor =
        DtoFactory.getInstance()
            .createDto(OldRecipeDescriptor.class)
            .withId(recipe.getId())
            .withName(recipe.getName())
            .withType(recipe.getType())
            .withScript(recipe.getScript())
            .withCreator(recipe.getCreator())
            .withTags(recipe.getTags());

    final UriBuilder builder = getServiceContext().getServiceUriBuilder();
    final Link removeLink =
        LinksHelper.createLink(
            "DELETE",
            builder.clone().path(getClass(), "removeRecipe").build(recipe.getId()).toString(),
            LINK_REL_REMOVE_RECIPE);
    final Link scriptLink =
        LinksHelper.createLink(
            "GET",
            builder.clone().path(getClass(), "getRecipeScript").build(recipe.getId()).toString(),
            TEXT_PLAIN,
            LINK_REL_GET_RECIPE_SCRIPT);
    descriptor.setLinks(asList(scriptLink, removeLink));
    return descriptor;
  }
}