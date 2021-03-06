<#--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
-->
<div class="panel panel-default" id="catalog_panel" role="tablist" aria-multiselectable="true">
  <div class="panel-heading" role="tab" id="catalog_panel_heading">
    <h5>
      <a data-toggle="collapse" data-parent="#catalog_panel" href="#catalog_panel_body" aria-expanded="true" aria-controls="collapse_catalog_panel_body">
        <i class="fa fa-lg fa-book"></i>&nbsp<b>${uiLabelMap.Catalog}</b>
      </a>
    </h5>
  </div>
  <div class="panel-body panel-collapse collapse in" id="catalog_panel_body" role="tabpanel" aria-labelledby="#catalog_panel_heading">
    <div class="row">
      <div class="col-lg-3 col-md-3">
      </div>
      <div class="col-lg-6 col-md-6">
        <div class="checkout-wrap">
          <#if parameters.nextStage?has_content>
            <#assign nextStage = parameters.nextStage/>
          <#else>
            <#assign nextStage = "syncCategories"/>
          </#if>
          <ul class="checkout-bar">
            <#if nextStage == "syncCategories">
              <li class="active">
                ${uiLabelMap.MagentoSyncCategories}
              </li>
              <li class="next">
                ${uiLabelMap.MagentoSyncProducts}
              </li>
            <#elseif nextStage == "syncProducts">
              <li class="visited">
                ${uiLabelMap.MagentoSyncCategories}
              </li>
              <li class="active">
                ${uiLabelMap.MagentoSyncProducts}
              </li>
            <#else>
              <li class="visited">
                ${uiLabelMap.MagentoSyncCategories}
              </li>
              <li class="visited">
                ${uiLabelMap.MagentoSyncProducts}
              </li>
            </#if>
          </ul>
        </div>
      </div>
    </div>
    <div class="row">
      <div class="col-lg-6 col-md-6">
        <div class="thumbnail">
          <div class="caption">
            <span <#if !(nextStage == "syncCategories")>class="text-muted"</#if>>
            <h5><b>${uiLabelMap.MagentoSyncCategories}</b></h5>
            <p>${uiLabelMap.MagentoSyncCategoryInfo}</p>
            <a class="btn btn-default" href="<@ofbizUrl>syncCategories</@ofbizUrl>" <#if !(nextStage == "syncCategories")> disabled=disabled </#if> data-dataSyncImage="Y">
              <i><img src="../img/interact.png"/></i> ${uiLabelMap.MagentoSync}
            </a>
            </span>
          </div>
        </div>
      </div>
      <div class="col-lg-6 col-md-6">
        <div class="thumbnail">
          <div class="caption">
            <span <#if !(nextStage == "syncProducts")>class="text-muted"</#if>>
            <h5><b>${uiLabelMap.MagentoSyncProducts}</b></h5>
            <p>${uiLabelMap.MagentoSyncProductInfo}</p>
            <a class="btn btn-default" href="<@ofbizUrl>syncProducts</@ofbizUrl>" <#if !(nextStage == "syncProducts")> disabled=disabled </#if> data-dataSyncImage="Y">
              <i><img src="../img/interact.png"/></i> ${uiLabelMap.MagentoSync}
            </a>
            </span>
          </div>
        </div>
      </div>
    </div>
  </div>
</div>