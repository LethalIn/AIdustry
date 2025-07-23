// Mindustry 1x1 Grabber Mechanics Script
// This script implements Factorio-style grabbers that move items between blocks without fuel

// Required sprites:
// - grabber-base: Static base of the grabber (1x1 tile)
// - grabber-arm: Rotating/moving arm segment
// - grabber-claw: End effector that picks up items

const grabber = extendContent(Block, "grabber",
 {
  // Block properties
  size: 1,
  hasItems: true,
  itemCapacity: 5,
  update: true,
  solid: true,
  configurable: true,
  saveConfig: true,
  rotate: true,
  rotateDraw: false,

  // Custom properties
  grabRange: 3,        // How far the grabber can reach
  grabSpeed: 0.08,     // Speed of arm movement (0-1)
  operationTime: 90,   // Ticks between operations

  init() {
    this.super$init();
    this.configurable = true;
  },

  // Initialize entity-specific variables
  entityTYpe: prov(() => {
    const entity = extend(TileEntity, {
      _targetPos: null,
      _armProgress: 0,
      _operationTimer: 0,
      _grabbedItem: null,
      _state: "idle", // idle, extending, grabbing, retracting, dropping
      _rotation: 0,
      
      // Save/load methods
      write(stream) {
        this.super$write(stream);
        stream.writeByte(this._rotation);
      },
      
      read(stream, revision) {
        this.super$read(stream, revision);
        this._rotation = stream.readByte();
      }
    });
    return entity;
  }),

  draw(tile) {
    const entity = tile.ent();
    
    // Draw base
    Draw.rect(this.region, tile.drawx(), tile.drawy());
    
    // Calculate arm rotation based on facing direction
    const rot = entity._rotation * 90;
    
    // Draw arm with extension animation
    const armExtend = entity._armProgress * this.grabRange * Vars.tilesize;
    const armOffsetX = Angles.trnsx(rot, armExtend / 2);
    const armOffsetY = Angles.trnsy(rot, armExtend / 2);
    
    // Draw arm segment
    if (entity._armProgress > 0) {
      Draw.rect(Core.atlas.find(this.name + "-arm") || this.region, 
                tile.drawx() + armOffsetX, 
                tile.drawy() + armOffsetY, 
                rot);
    }
    
    // Draw claw at end of arm
    if (entity._armProgress > 0) {
      const clawX = tile.drawx() + Angles.trnsx(rot, armExtend);
      const clawY = tile.drawy() + Angles.trnsy(rot, armExtend);
      Draw.rect(Core.atlas.find(this.name + "-claw") || this.region, clawX, clawY, rot);
      
      // Draw grabbed item if any
      if (entity._grabbedItem != null && entity._state !== "idle") {
        Draw.rect(entity._grabbedItem.icon(Cicon.small), clawX, clawY);
      }
    }
  },

  update(tile) {
    const entity = tile.ent();
    
    // Timer management
    if (entity._operationTimer > 0) {
      entity._operationTimer--;
      return;
    }

    // State machine for grabber operation
    switch (entity._state) {
      case "idle":
        // Find input blocks in front
        const target = this.findInputTile(tile);
        if (target) {
          entity._targetPos = target;
          entity._state = "extending";
        } else {
          // Reset periodically even if no target
          entity._armProgress = 0;
        }
        break;
        
      case "extending":
        // Extend arm towards target
        entity._armProgress += this.grabSpeed;
        if (entity._armProgress >= 1) {
          entity._armProgress = 1;
          entity._state = "grabbing";
        }
        break;
        
      case "grabbing":
        // Try to grab an item from target
        if (entity._targetPos) {
          const targetTile = Vars.world.tile(entity._targetPos.x, entity._targetPos.y);
          if (targetTile && targetTile.entity && targetTile.entity.items.total() > 0) {
            // Get first available item
            const item = targetTile.entity.items.take();
            if (item) {
              entity._grabbedItem = item;
              entity._state = "retracting";
            } else {
              // Nothing to grab, reset
              this.resetGrabber(entity);
            }
          } else {
            // Target no longer valid
            this.resetGrabber(entity);
          }
        } else {
          this.resetGrabber(entity);
        }
        break;
        
      case "retracting":
        // Retract arm back
        entity._armProgress -= this.grabSpeed;
        if (entity._armProgress <= 0) {
          entity._armProgress = 0;
          entity._state = "dropping";
        }
        break;
        
      case "dropping":
        // Drop item into grabber's inventory
        if (entity._grabbedItem && entity.items.total() < this.itemCapacity) {
          entity.items.add(entity._grabbedItem, 1);
          entity._grabbedItem = null;
        }
        this.resetGrabber(entity);
        entity._operationTimer = this.operationTime;
        break;
    }
  },

  findInputTile(tile) {
    const entity = tile.ent();
    const dir = entity._rotation;
    const dx = Geometry.d4x(dir);
    const dy = Geometry.d4y(dir);
    
    // Check tiles in front of grabber
    for (let i = 1; i <= this.grabRange; i++) {
      const tx = tile.x + dx * i;
      const ty = tile.y + dy * i;
      const checkTile = Vars.world.tile(tx, ty);
      
      if (checkTile && checkTile.block() && checkTile.entity) {
        // Check if it's an output block with items
        if (checkTile.block().outputsItems && checkTile.entity.items.total() > 0) {
          return new Point2(tx, ty);
        }
      }
    }
    
    return null;
  },

  resetGrabber(entity) {
    entity._state = "idle";
    entity._armProgress = 0;
    entity._targetPos = null;
  },

  configured(player, tile, value) {
    // Configure rotation
    tile.ent()._rotation = value;
  },

  drawConfigure(tile) {
    const entity = tile.ent();
    const x = tile.drawx();
    const y = tile.drawy();
    
    // Draw selection
    Draw.color(Pal.accent);
    Lines.stroke(1);
    Lines.square(x, y, this.size * Vars.tilesize / 2 + 1);
    
    // Draw range indicator
    const dir = entity._rotation;
    const dx = Geometry.d4x(dir);
    const dy = Geometry.d4y(dir);
    const rangeX = x + dx * this.grabRange * Vars.tilesize;
    const rangeY = y + dy * this.grabRange * Vars.tilesize;
    
    Lines.square(rangeX, rangeY, Vars.tilesize / 2);
    Lines.stroke(2);
    Lines.line(x, y, rangeX, rangeY);
    
    Draw.color();
  },

  // Setup for placement
  drawPlace(x, y, rotation, valid) {
    const tile = Vars.world.tile(x, y);
    if (!tile) return;
    
    Draw.color(valid ? Pal.accent : Pal.remove);
    Lines.stroke(2);
    
    // Draw range preview
    const dx = Geometry.d4x(rotation);
    const dy = Geometry.d4y(rotation);
    const rangeX = (x + dx * this.grabRange) * Vars.tilesize;
    const rangeY = (y + dy * this.grabRange) * Vars.tilesize;
    
    Lines.square(rangeX, rangeY, Vars.tilesize / 2);
    Lines.line(x * Vars.tilesize, y * Vars.tilesize, rangeX, rangeY);
    
    Draw.color();
  }
});

// Register the block
Events.on(ContentInitEvent, cons(e => {
  grabber.localizedName = "Grabber";
  grabber.description = "Grabs items from blocks in front and stores them. Works like Factorio inserters but without fuel.";
  grabber.details = "Can reach up to " + grabber.grabRange + " tiles away. Automatically grabs items from output blocks.";
}));
