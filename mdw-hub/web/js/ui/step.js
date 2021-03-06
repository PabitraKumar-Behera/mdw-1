'use strict';

var stepMod = angular.module('mdwStep', ['mdw']);

stepMod.factory('Step', ['mdw', 'util', 'Shape', 'DC', 'WORKFLOW_STATUSES',
                         function(mdw, util, Shape, DC, WORKFLOW_STATUSES) {
  
  var Step = function(diagram, activity) {
    Shape.call(this, diagram, activity);
    this.diagram = diagram;
    this.activity = activity;
    this.workflowType = 'activity';
    this.isStep = true;
  };
  
  Step.prototype = new Shape();
  
  Step.INST_W = 8;
  Step.OLD_INST_W = 4;
  Step.MAX_INSTS = 10;
  Step.MIN_SIZE = 4;
  
  Step.STATUSES = [{status: 'Unknown', color: 'transparent'}].concat(WORKFLOW_STATUSES);
  
  Step.prototype.draw = function() {
    var activity = this.workflowObj = this.activity;
    var shape;
    if (this.implementor.icon && this.implementor.icon.startsWith('shape:'))
      shape = this.implementor.icon.substring(6);
    
    // runtime state first
    if (this.instances) {
      var adj = 0;
      if (shape == 'start' || shape == 'stop')
        adj = 2;
      this.diagram.drawState(this.display, this.instances, !this.diagram.drawBoxes, adj);
    }
    
    if (this.implementor.icon) {
      var yAdjust = -2;
      if (shape) {
        if ('start' == shape) {
          this.diagram.drawOval(this.display.x, this.display.y, this.display.w, this.display.h, 'green', 'white');
        }
        else if ('stop' == shape) {
          this.diagram.drawOval(this.display.x, this.display.y, this.display.w, this.display.h, 'red', 'white');
        }
        else if ('decision' == shape) {
          this.diagram.drawDiamond(this.display.x, this.display.y, this.display.w, this.display.h);
          yAdjust = -8;
        }
        else if ('activity' == shape) {
          this.diagram.roundedRect(this.display.x, this.display.y, this.display.w, this.display.h, DC.BOX_OUTLINE_COLOR);
        }
      }
      else {
        if (this.diagram.drawBoxes)
          this.diagram.roundedRect(this.display.x, this.display.y, this.display.w, this.display.h, DC.BOX_OUTLINE_COLOR);
        var iconSrc = 'asset/' + this.implementor.icon;
        var iconX = this.display.x + this.display.w / 2 - 12;
        var iconY = this.display.y + 5;
        this.diagram.drawImage(iconSrc, iconX, iconY);
        yAdjust = +4; 
      }
    }
    else {
      this.diagram.roundedRect(this.display.x, this.display.y, this.display.w, this.display.h, DC.BOX_OUTLINE_COLOR);
    }

    // title
    var diagram = this.diagram;
    this.title.lines.forEach(function(line) {
      diagram.context.fillText(line.text, line.x, line.y + yAdjust);
    });
    
    // logical id
    this.diagram.context.fillStyle = DC.META_COLOR;
    this.diagram.context.fillText(activity.id, this.display.x + 2, this.display.y - 2);
    this.diagram.context.fillStyle = DC.DEFAULT_COLOR;
    
  };
  
  // sets display/title and returns an object with w and h for required size
  Step.prototype.prepareDisplay = function() {
    var maxDisplay = { w: 0, h: 0};
    var display = this.getDisplay();

    if (display.x + display.w > maxDisplay.w)
      maxDisplay.w = display.x + display.w;
    if (display.y + display.h > maxDisplay.h)
      maxDisplay.h = display.y + display.h;
    
    // step title
    var titleLines = [];
    this.activity.name.getLines().forEach(function(line) {
      titleLines.push({ text: line });
    });
    var title = { text: this.activity.name, lines: titleLines, w: 0, h:0 };
    for (var i = 0; i < title.lines.length; i++) {
      var line = title.lines[i];
      var textMetrics = this.diagram.context.measureText(line.text);
      if (textMetrics.width > title.w)
        title.w = textMetrics.width;
      title.h += DC.DEFAULT_FONT.SIZE;
      line.x = display.x + display.w / 2 - textMetrics.width / 2;
      line.y = display.y + display.h / 2 + DC.DEFAULT_FONT.SIZE * (i + 0.5);
      if (line.x + textMetrics.width > maxDisplay.w)
        maxDisplay.w = line.x + textMetrics.width;
      if (line.y + DC.DEFAULT_FONT.SIZE > maxDisplay.h)
        maxDisplay.h = line.y + DC.DEFAULT_FONT.SIZE;
    }

    this.display = display;
    this.title = title;    
    
    return maxDisplay;
  };
  
  Step.prototype.applyState = function(activityInstances) {
    this.instances = activityInstances;
  };

  Step.prototype.move = function(deltaX, deltaY, limDisplay) {
    var x = this.display.x + deltaX;
    var y = this.display.y + deltaY;
    if (limDisplay) {
      if (x < limDisplay.x)
        x = limDisplay.x;
      else if (x > limDisplay.x + limDisplay.w - this.display.w)
        x = limDisplay.x + limDisplay.w - this.display.w;
      if (y < limDisplay.y)
        y = limDisplay.y;
      else if (y > limDisplay.y + limDisplay.h - this.display.h)
        y = limDisplay.y + limDisplay.h - this.display.h;
    }
    this.setDisplayAttr(x, y, this.display.w, this.display.h);
  };
  
  Step.prototype.resize = function(x, y, deltaX, deltaY, limDisplay) {
    var display = this.resizeDisplay(x, y, deltaX, deltaY, Step.MIN_SIZE, limDisplay);
    this.activity.attributes.WORK_DISPLAY_INFO = this.getAttr(display);
  };
  
  return Step;
    
}]);
